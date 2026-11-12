package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.paymentlink.GeneratedPaymentLinkResponse;
import com.paypilot.dto.paymentlink.PaymentLinkSummaryResponse;
import com.paypilot.dto.publicpay.PublicLinkState;
import com.paypilot.dto.publicpay.PublicPaymentInvoiceResponse;
import com.paypilot.dto.publicpay.PublicPaymentReceiptResponse;
import com.paypilot.dto.publicpay.PublicPaymentSummary;
import com.paypilot.exception.ForbiddenOperationException;
import com.paypilot.exception.InvalidInvoiceStateException;
import com.paypilot.exception.PaymentLinkGoneException;
import com.paypilot.exception.ResourceNotFoundException;
import com.paypilot.model.AuditActor;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentLink;
import com.paypilot.model.PaymentStatus;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Authoritative service for generating, rotating, revoking, and validating
 * cryptographically random customer payment links.
 *
 * SECURITY GUARANTEES:
 *   - Uses 256-bit SecureRandom tokens encoded as URL-safe Base64 (43 chars).
 *   - Persists only SHA-256 token hashes; raw tokens are never saved or logged.
 *   - Binds each link to the approved invoice_snapshot_hash and seller user.
 *   - Enforces at most one active link per invoice via uq_payment_links_active_invoice.
 */
@Service
public class PaymentLinkService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32; // 256 bits
    private static final int MAX_GENERATION_ATTEMPTS = 5;
    private static final int MIN_TTL_DAYS = 1;
    private static final int MAX_TTL_DAYS = 90;
    private static final Pattern RAW_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{32,128}$");
    private static final Pattern ISO_CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
    private static final List<PaymentStatus> ACTIVE_UNCAPTURED_STATUSES =
        List.of(PaymentStatus.CREATED, PaymentStatus.APPROVED);

    private final PaymentLinkRepository paymentLinkRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceSnapshotService invoiceSnapshotService;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final int defaultTtlDays;
    private final long approvalMaxAgeHours;
    private final String defaultFrontendOrigin;

    public PaymentLinkService(
            PaymentLinkRepository paymentLinkRepository,
            InvoiceRepository invoiceRepository,
            PaymentRepository paymentRepository,
            InvoiceSnapshotService invoiceSnapshotService,
            AuditLogService auditLogService,
            @Qualifier("paymentClock") Clock clock,
            @Value("${payment-link.default-ttl-days:14}") int defaultTtlDays,
            @Value("${payment.approval-max-age-hours:168}") long approvalMaxAgeHours,
            @Value("${cors.allowed-origins:http://localhost:5173}") String allowedOriginsRaw) {
        this.paymentLinkRepository = paymentLinkRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.invoiceSnapshotService = invoiceSnapshotService;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.defaultTtlDays = clampTtlDays(defaultTtlDays);
        this.approvalMaxAgeHours = Math.max(1L, approvalMaxAgeHours);
        this.defaultFrontendOrigin = resolveDefaultOrigin(allowedOriginsRaw);
    }

    @Transactional
    public GeneratedPaymentLinkResponse generateOrRotateLink(
            Long authenticatedUserId,
            Long invoiceId,
            Integer expiresInDays,
            String ipAddress) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));

        if (!invoice.getUser().getId().equals(authenticatedUserId)) {
            throw new ForbiddenOperationException("You do not have permission to generate a payment link for this invoice.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        validateInvoiceEligibleForLink(invoice, now);

        if (paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.CAPTURED)) {
            throw new InvalidInvoiceStateException("Invoice has already been paid.");
        }
        if (paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.PENDING)) {
            throw new InvalidInvoiceStateException("Invoice has a pending PayPal capture and cannot rotate payment links right now.");
        }

        int ttlDays = resolveRequestedTtlDays(expiresInDays);
        LocalDateTime expiresAt = now.plusDays(ttlDays);

        boolean rotated = false;
        Optional<PaymentLink> existingActive = paymentLinkRepository.findByActiveInvoiceId(invoiceId);
        if (existingActive.isPresent()) {
            PaymentLink previous = existingActive.get();
            previous.revoke(now, "ROTATED_BY_SELLER");
            paymentLinkRepository.saveAndFlush(previous);
            rotated = true;
        }

        String rawToken = null;
        String tokenHash = null;
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = generateRawToken();
            String candidateHash = hashRawToken(candidate);
            if (!paymentLinkRepository.existsByTokenHash(candidateHash)) {
                rawToken = candidate;
                tokenHash = candidateHash;
                break;
            }
        }
        if (rawToken == null || tokenHash == null) {
            throw new IllegalStateException("Unable to generate a unique payment link token. Please retry.");
        }

        PaymentLink link = PaymentLink.builder()
            .invoice(invoice)
            .user(invoice.getUser())
            .tokenHash(tokenHash)
            .tokenPrefix(buildDisplayPrefix(rawToken))
            .invoiceSnapshotHash(invoice.getApprovedSnapshotHash())
            .expiresAt(expiresAt)
            .build();

        PaymentLink saved = paymentLinkRepository.saveAndFlush(link);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("invoiceId", invoice.getId());
        auditDetails.put("invoiceNumber", invoice.getInvoiceNumber());
        auditDetails.put("paymentLinkId", saved.getId());
        auditDetails.put("tokenPrefix", saved.getTokenPrefix());
        auditDetails.put("expiresAt", saved.getExpiresAt().toString());
        auditDetails.put("rotated", rotated);

        auditLogService.logEvent(
            authenticatedUserId,
            "PAYMENT_LINK",
            saved.getId(),
            rotated ? "PAYMENT_LINK_ROTATED" : "PAYMENT_LINK_GENERATED",
            AuditActor.USER,
            auditDetails,
            ipAddress
        );

        String paymentUrl = defaultFrontendOrigin + "/pay/" + rawToken;
        return new GeneratedPaymentLinkResponse(
            PaymentLinkSummaryResponse.from(saved, now),
            paymentUrl,
            rawToken
        );
    }

    @Transactional
    public PaymentLinkSummaryResponse revokeActiveLink(
            Long authenticatedUserId,
            Long invoiceId,
            String reason,
            String ipAddress) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));

        if (!invoice.getUser().getId().equals(authenticatedUserId)) {
            throw new ForbiddenOperationException("You do not have permission to revoke payment links for this invoice.");
        }

        PaymentLink active = paymentLinkRepository.findByActiveInvoiceId(invoiceId)
            .orElseThrow(() -> new InvalidInvoiceStateException("No active payment link exists for this invoice."));

        LocalDateTime now = LocalDateTime.now(clock);
        String normalizedReason = (reason == null || reason.isBlank()) ? "REVOKED_BY_SELLER" : reason.trim();
        active.revoke(now, normalizedReason);
        PaymentLink saved = paymentLinkRepository.saveAndFlush(active);

        auditLogService.logEvent(
            authenticatedUserId,
            "PAYMENT_LINK",
            saved.getId(),
            "PAYMENT_LINK_REVOKED",
            AuditActor.USER,
            Map.of(
                "invoiceId", invoice.getId(),
                "invoiceNumber", invoice.getInvoiceNumber(),
                "tokenPrefix", saved.getTokenPrefix(),
                "reason", saved.getRevocationReason() != null ? saved.getRevocationReason() : normalizedReason
            ),
            ipAddress
        );

        return PaymentLinkSummaryResponse.from(saved, now);
    }

    @Transactional(readOnly = true)
    public List<PaymentLinkSummaryResponse> listLinksForInvoice(Long authenticatedUserId, Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));

        if (!invoice.getUser().getId().equals(authenticatedUserId)) {
            throw new ForbiddenOperationException("You do not have permission to view payment links for this invoice.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        return paymentLinkRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
            .map(link -> PaymentLinkSummaryResponse.from(link, now))
            .toList();
    }

    /**
     * Loads the customer-facing view of an invoice for GET /api/public/pay/{token}.
     * Redacts invoice details whenever a link is REVOKED, EXPIRED, or UNAVAILABLE.
     */
    @Transactional
    public PublicPaymentInvoiceResponse getPublicPaymentView(String rawToken) {
        return buildPublicPaymentView(rawToken, true);
    }

    /**
     * Builds the sanitized customer-facing view, optionally recording a view access increment.
     */
    @Transactional
    public PublicPaymentInvoiceResponse buildPublicPaymentView(String rawToken, boolean recordAccess) {
        String tokenHash = validateAndHashRawTokenOrThrowNotFound(rawToken);
        PaymentLink link = paymentLinkRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> new ResourceNotFoundException("Payment link not found."));

        LocalDateTime now = LocalDateTime.now(clock);
        Invoice invoice = link.getInvoice();

        if (link.isRevoked()) {
            return redactedResponse(
                PublicLinkState.REVOKED,
                null,
                "This payment link has been revoked by the seller and can no longer be used."
            );
        }

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            Optional<Payment> capturedOpt = paymentRepository.findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(
                invoice.getId(), PaymentStatus.CAPTURED
            );
            if (capturedOpt.isEmpty() || capturedOpt.get().getPaypalCaptureId() == null) {
                return redactedResponse(
                    PublicLinkState.UNAVAILABLE,
                    null,
                    "Invoice payment state requires seller verification before viewing a receipt."
                );
            }
            Payment captured = capturedOpt.get();
            if (recordAccess) {
                link.recordAccess(now);
                paymentLinkRepository.saveAndFlush(link);
            }

            String sellerDisplayName = safeSellerDisplayName(invoice);
            PublicPaymentReceiptResponse receipt = new PublicPaymentReceiptResponse(
                invoice.getInvoiceNumber(),
                sellerDisplayName,
                invoice.getClientName(),
                invoice.getDescription(),
                captured.getAmount(),
                captured.getCurrency(),
                captured.getPaypalOrderId(),
                captured.getPaypalCaptureId(),
                captured.getStatus(),
                captured.getCapturedAt() != null ? captured.getCapturedAt() : invoice.getPaidAt(),
                true
            );

            return new PublicPaymentInvoiceResponse(
                PublicLinkState.PAID,
                false,
                true,
                "Payment verified and completed in PayPal Sandbox.",
                link.getExpiresAt(),
                invoice.getInvoiceNumber(),
                sellerDisplayName,
                invoice.getClientName(),
                invoice.getDescription(),
                invoice.getAmount(),
                invoice.getCurrency(),
                invoice.getDueDate(),
                invoice.getStatus(),
                new PublicPaymentSummary(
                    captured.getPaypalOrderId(),
                    captured.getStatus(),
                    null,
                    null,
                    captured.getUpdatedAt()
                ),
                receipt
            );
        }

        if (link.isExpired(now)) {
            return redactedResponse(
                PublicLinkState.EXPIRED,
                link.getExpiresAt(),
                "This payment link has expired. Please contact the seller for a fresh payment link."
            );
        }

        Optional<PublicLinkState> blockReason = evaluatePublicAvailabilityIssue(invoice, link, now);
        if (blockReason.isPresent()) {
            PublicLinkState state = blockReason.get();
            String message = state == PublicLinkState.EXPIRED
                ? "This invoice due date or approval window has expired. Please contact the seller for an updated link."
                : "This invoice is currently unavailable for payment because its details or approval changed.";
            return redactedResponse(state, state == PublicLinkState.EXPIRED ? link.getExpiresAt() : null, message);
        }

        if (recordAccess) {
            link.recordAccess(now);
            paymentLinkRepository.saveAndFlush(link);
        }

        PublicPaymentSummary activeSummary = resolveCustomerPaymentSummary(invoice.getId(), link.getId());
        boolean pendingCapture = activeSummary != null && activeSummary.status() == PaymentStatus.PENDING;

        String statusMessage = pendingCapture
            ? "Your PayPal Sandbox payment is awaiting final verification. Refresh or re-check status without creating a new order."
            : "Invoice verified and ready for secure PayPal Sandbox payment.";

        return new PublicPaymentInvoiceResponse(
            PublicLinkState.ACTIVE,
            !pendingCapture,
            true,
            statusMessage,
            link.getExpiresAt(),
            invoice.getInvoiceNumber(),
            safeSellerDisplayName(invoice),
            invoice.getClientName(),
            invoice.getDescription(),
            invoice.getAmount(),
            invoice.getCurrency(),
            invoice.getDueDate(),
            invoice.getStatus(),
            activeSummary,
            null
        );
    }

    /**
     * Resolves and validates a token hash for mutating customer checkout operations.
     * Fails closed with 404 (unknown token), 410 (revoked/expired), or 409 (unapproved/modified).
     */
    @Transactional(readOnly = true)
    public ValidatedLinkContext resolveLinkContextForCheckout(String rawToken, boolean allowPaidReplay) {
        String tokenHash = validateAndHashRawTokenOrThrowNotFound(rawToken);
        PaymentLink link = paymentLinkRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> new ResourceNotFoundException("Payment link not found."));

        if (link.isRevoked()) {
            throw new PaymentLinkGoneException("This payment link has been revoked and can no longer be used.");
        }

        Invoice invoice = link.getInvoice();
        LocalDateTime now = LocalDateTime.now(clock);

        if (allowPaidReplay && invoice.getStatus() == InvoiceStatus.PAID) {
            return new ValidatedLinkContext(
                link.getId(),
                invoice.getId(),
                invoice.getUser().getId(),
                link.getInvoiceSnapshotHash(),
                link.getTokenPrefix()
            );
        }

        if (link.isExpired(now)) {
            throw new PaymentLinkGoneException("This payment link has expired. Please request a new link from the seller.");
        }

        validateInvoiceBoundToLink(invoice, link, now);

        return new ValidatedLinkContext(
            link.getId(),
            invoice.getId(),
            invoice.getUser().getId(),
            link.getInvoiceSnapshotHash(),
            link.getTokenPrefix()
        );
    }

    public String hashRawTokenOrThrow(String rawToken) {
        return validateAndHashRawTokenOrThrowNotFound(rawToken);
    }

    public boolean verifyRawTokenMatchesLink(String rawToken, PaymentLink link) {
        if (link == null || link.getTokenHash() == null || rawToken == null || !RAW_TOKEN_PATTERN.matcher(rawToken).matches()) {
            return false;
        }
        String candidateHash = hashRawToken(rawToken);
        return MessageDigest.isEqual(
            candidateHash.getBytes(StandardCharsets.UTF_8),
            link.getTokenHash().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    private PublicPaymentSummary resolveCustomerPaymentSummary(Long invoiceId, Long paymentLinkId) {
        Optional<Payment> pendingOpt = paymentRepository.findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(
            invoiceId, PaymentStatus.PENDING
        );
        if (pendingOpt.isPresent()) {
            Payment pending = pendingOpt.get();
            if (pending.getPaymentLink() != null && pending.getPaymentLink().getId().equals(paymentLinkId)) {
                return toSummary(pending);
            }
            return new PublicPaymentSummary(
                pending.getPaypalOrderId(),
                pending.getStatus(),
                extractPendingReason(pending),
                null,
                pending.getUpdatedAt()
            );
        }

        List<Payment> activeOrders = paymentRepository.findByInvoiceIdAndStatusIn(
            invoiceId, ACTIVE_UNCAPTURED_STATUSES
        );
        return activeOrders.stream()
            .filter(p -> p.getPaymentLink() != null && p.getPaymentLink().getId().equals(paymentLinkId))
            .findFirst()
            .map(this::toSummary)
            .orElse(null);
    }

    private PublicPaymentSummary toSummary(Payment payment) {
        return new PublicPaymentSummary(
            payment.getPaypalOrderId(),
            payment.getStatus(),
            extractPendingReason(payment),
            payment.getPaypalApprovalUrl(),
            payment.getUpdatedAt()
        );
    }

    private String extractPendingReason(Payment payment) {
        if (payment.getPaypalResponseSummary() == null) {
            return null;
        }
        Object raw = payment.getPaypalResponseSummary().get("pendingReason");
        return raw instanceof String str && !str.isBlank() ? str : null;
    }

    private void validateInvoiceEligibleForLink(Invoice invoice, LocalDateTime now) {
        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            throw new InvalidInvoiceStateException(
                "Payment links can only be generated for APPROVED invoices. Current status: " + invoice.getStatus()
            );
        }
        if (invoice.getApprovedAt() == null
                || invoice.getApprovedByUserId() == null
                || !invoice.getApprovedByUserId().equals(invoice.getUser().getId())
                || invoice.getApprovedSnapshotHash() == null
                || invoice.getApprovedSnapshotHash().isBlank()) {
            throw new InvalidInvoiceStateException("Invoice is missing valid seller approval metadata.");
        }
        if (invoice.getApprovedAt().plusHours(approvalMaxAgeHours).isBefore(now)) {
            throw new InvalidInvoiceStateException("Invoice approval has expired. Please re-approve before generating a link.");
        }
        if (invoice.getDueDate() == null || invoice.getDueDate().isBefore(now.toLocalDate())) {
            throw new InvalidInvoiceStateException("Invoice due date has passed. Update and re-approve before generating a link.");
        }
        if (invoice.getAmount() == null || invoice.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidInvoiceStateException("Invoice amount must be positive.");
        }
        if (invoice.getCurrency() == null
                || !ISO_CURRENCY_PATTERN.matcher(invoice.getCurrency().trim().toUpperCase(Locale.ROOT)).matches()) {
            throw new InvalidInvoiceStateException("Invoice currency must be a valid 3-letter ISO code.");
        }
        if (invoice.getDescription() == null || invoice.getDescription().isBlank()) {
            throw new InvalidInvoiceStateException("Invoice description is required.");
        }
        String currentHash = invoiceSnapshotService.computeHash(invoice);
        if (!invoiceSnapshotService.hashesMatch(currentHash, invoice.getApprovedSnapshotHash())) {
            throw new InvalidInvoiceStateException("Invoice details no longer match the approved snapshot. Please re-approve.");
        }
    }

    private Optional<PublicLinkState> evaluatePublicAvailabilityIssue(
            Invoice invoice,
            PaymentLink link,
            LocalDateTime now) {
        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            return Optional.of(PublicLinkState.UNAVAILABLE);
        }
        if (invoice.getApprovedAt() == null
                || invoice.getApprovedByUserId() == null
                || !invoice.getApprovedByUserId().equals(invoice.getUser().getId())
                || invoice.getApprovedSnapshotHash() == null) {
            return Optional.of(PublicLinkState.UNAVAILABLE);
        }
        String currentHash = invoiceSnapshotService.computeHash(invoice);
        if (!invoiceSnapshotService.hashesMatch(currentHash, invoice.getApprovedSnapshotHash())
                || !invoiceSnapshotService.hashesMatch(currentHash, link.getInvoiceSnapshotHash())) {
            return Optional.of(PublicLinkState.UNAVAILABLE);
        }
        if (invoice.getApprovedAt().plusHours(approvalMaxAgeHours).isBefore(now)) {
            return Optional.of(PublicLinkState.EXPIRED);
        }
        if (invoice.getDueDate() == null || invoice.getDueDate().isBefore(now.toLocalDate())) {
            return Optional.of(PublicLinkState.EXPIRED);
        }
        return Optional.empty();
    }

    private void validateInvoiceBoundToLink(Invoice invoice, PaymentLink link, LocalDateTime now) {
        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            throw new InvalidInvoiceStateException(
                "Invoice is not available for payment (status: " + invoice.getStatus() + ")."
            );
        }
        if (invoice.getApprovedAt() == null
                || invoice.getApprovedByUserId() == null
                || !invoice.getApprovedByUserId().equals(invoice.getUser().getId())
                || invoice.getApprovedSnapshotHash() == null) {
            throw new InvalidInvoiceStateException("Invoice is missing valid seller approval.");
        }
        String currentHash = invoiceSnapshotService.computeHash(invoice);
        if (!invoiceSnapshotService.hashesMatch(currentHash, invoice.getApprovedSnapshotHash())
                || !invoiceSnapshotService.hashesMatch(currentHash, link.getInvoiceSnapshotHash())) {
            throw new InvalidInvoiceStateException(
                "Invoice details changed after this payment link was created. Please request a fresh link."
            );
        }
        if (invoice.getApprovedAt().plusHours(approvalMaxAgeHours).isBefore(now)) {
            throw new PaymentLinkGoneException("Invoice approval window has expired.");
        }
        if (invoice.getDueDate() == null || invoice.getDueDate().isBefore(now.toLocalDate())) {
            throw new PaymentLinkGoneException("Invoice due date has expired.");
        }
    }

    private PublicPaymentInvoiceResponse redactedResponse(
            PublicLinkState state,
            LocalDateTime expiresAt,
            String message) {
        return new PublicPaymentInvoiceResponse(
            state,
            false,
            true,
            message,
            expiresAt,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private String safeSellerDisplayName(Invoice invoice) {
        if (invoice.getUser() == null || invoice.getUser().getName() == null || invoice.getUser().getName().isBlank()) {
            return "PayPilot Merchant";
        }
        return invoice.getUser().getName().trim();
    }

    private String validateAndHashRawTokenOrThrowNotFound(String rawToken) {
        if (rawToken == null || !RAW_TOKEN_PATTERN.matcher(rawToken).matches()) {
            throw new ResourceNotFoundException("Payment link not found.");
        }
        return hashRawToken(rawToken);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashRawToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable.", ex);
        }
    }

    private String buildDisplayPrefix(String rawToken) {
        String snippet = rawToken.length() >= 6 ? rawToken.substring(0, 6) : rawToken;
        return "ppl_" + snippet + "...";
    }

    private int resolveRequestedTtlDays(Integer expiresInDays) {
        if (expiresInDays == null) {
            return defaultTtlDays;
        }
        if (expiresInDays < MIN_TTL_DAYS || expiresInDays > MAX_TTL_DAYS) {
            throw new IllegalArgumentException(
                "Payment link expiration must be between " + MIN_TTL_DAYS + " and " + MAX_TTL_DAYS + " days."
            );
        }
        return expiresInDays;
    }

    private int clampTtlDays(int raw) {
        return Math.max(MIN_TTL_DAYS, Math.min(MAX_TTL_DAYS, raw));
    }

    private String resolveDefaultOrigin(String allowedOriginsRaw) {
        if (allowedOriginsRaw == null || allowedOriginsRaw.isBlank()) {
            return "http://localhost:5173";
        }
        return Arrays.stream(allowedOriginsRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .findFirst()
            .orElse("http://localhost:5173");
    }

    public record ValidatedLinkContext(
        Long paymentLinkId,
        Long invoiceId,
        Long sellerUserId,
        String linkSnapshotHash,
        String tokenPrefix
    ) {
    }
}
