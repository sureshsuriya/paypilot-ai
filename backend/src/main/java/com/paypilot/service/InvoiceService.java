package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.invoice.ApproveInvoiceRequest;
import com.paypilot.dto.invoice.CreateInvoiceRequest;
import com.paypilot.dto.invoice.InvoiceResponse;
import com.paypilot.dto.invoice.UpdateInvoiceRequest;
import com.paypilot.exception.ForbiddenOperationException;
import com.paypilot.exception.InvalidInvoiceStateException;
import com.paypilot.exception.ResourceNotFoundException;
import com.paypilot.model.AiExtractionLog;
import com.paypilot.model.AuditActor;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentOrderIntent;
import com.paypilot.model.PaymentOrderIntentStatus;
import com.paypilot.model.PaymentStatus;
import com.paypilot.model.User;
import com.paypilot.repository.AiExtractionLogRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentOrderIntentRepository;
import com.paypilot.repository.PaymentRepository;
import com.paypilot.repository.UserRepository;
import com.paypilot.util.PhoneNumberValidator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Authoritative service for invoice creation, ownership enforcement,
 * explicit user approval/revocation, approval invalidation on edit,
 * and immutable audit logging.
 */
@Service
public class InvoiceService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String INVOICE_CODE_CHARS = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int INVOICE_CODE_LENGTH = 6;
    private static final int MAX_GENERATION_ATTEMPTS = 10;
    private static final long MUTATION_LEASE_SECONDS = 10L;
    private static final DateTimeFormatter DATE_PREFIX_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<PaymentStatus> UNCAPTURED_ORDER_STATUSES =
        List.of(PaymentStatus.CREATED, PaymentStatus.APPROVED);

    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentOrderIntentRepository paymentOrderIntentRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final AiExtractionLogRepository extractionLogRepository;
    private final AuditLogService auditLogService;
    private final InvoiceSnapshotService invoiceSnapshotService;
    private final CustomerService customerService;
    private final Validator validator;
    private final Clock paymentClock;

    @Autowired
    public InvoiceService(
            InvoiceRepository invoiceRepository,
            UserRepository userRepository,
            PaymentRepository paymentRepository,
            PaymentOrderIntentRepository paymentOrderIntentRepository,
            PaymentLinkRepository paymentLinkRepository,
            AiExtractionLogRepository extractionLogRepository,
            AuditLogService auditLogService,
            InvoiceSnapshotService invoiceSnapshotService,
            CustomerService customerService,
            Validator validator,
            @Qualifier("paymentClock") Clock paymentClock) {
        this.invoiceRepository = invoiceRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.paymentOrderIntentRepository = paymentOrderIntentRepository;
        this.paymentLinkRepository = paymentLinkRepository;
        this.extractionLogRepository = extractionLogRepository;
        this.auditLogService = auditLogService;
        this.invoiceSnapshotService = invoiceSnapshotService;
        this.customerService = customerService;
        this.validator = validator;
        this.paymentClock = paymentClock != null ? paymentClock : Clock.systemDefaultZone();
    }

    @Transactional
    public InvoiceResponse createInvoice(
            Long authenticatedUserId,
            CreateInvoiceRequest request,
            String ipAddress) {

        User owner = userRepository.findById(authenticatedUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found."));

        AiExtractionLog extractionLog = null;
        if (request.extractionLogId() != null) {
            extractionLog = extractionLogRepository.findById(request.extractionLogId())
                .orElseThrow(() -> new ResourceNotFoundException("AI extraction log not found."));
            if (extractionLog.getInvoice() != null) {
                throw new InvalidInvoiceStateException("This AI extraction log is already linked to an invoice.");
            }
        }

        String invoiceNumber = generateUniqueInvoiceNumber(owner.getId());

        String normalizedPhone = PhoneNumberValidator.normalizeOptional(request.clientPhone());

        Invoice invoice = Invoice.builder()
            .user(owner)
            .invoiceNumber(invoiceNumber)
            .status(InvoiceStatus.DRAFT)
            .issuedAt(LocalDate.now(paymentClock))
            .aiExtracted(extractionLog != null)
            .aiConfidence(extractionLog != null ? extractionLog.getConfidenceScore() : null)
            .build();

        applyValidatedFields(
            invoice,
            request.clientName(),
            request.clientEmail(),
            normalizedPhone,
            request.description(),
            request.amount(),
            request.currency(),
            request.dueDate()
        );

        if (customerService != null) {
            customerService.resolveAndLinkForInvoice(owner, invoice, request.customerId(), normalizedPhone, true);
        }

        Invoice saved = invoiceRepository.save(invoice);

        if (extractionLog != null) {
            extractionLog.setInvoice(saved);
            extractionLog.setValidatedByUser(Boolean.TRUE);
            extractionLogRepository.save(extractionLog);
        }

        auditLogService.logEvent(
            owner.getId(),
            "INVOICE",
            saved.getId(),
            "INVOICE_CREATED",
            AuditActor.USER,
            Map.of(
                "invoiceNumber", saved.getInvoiceNumber(),
                "amount", saved.getAmount().toPlainString(),
                "currency", saved.getCurrency(),
                "status", saved.getStatus().name(),
                "dueDate", saved.getDueDate().toString(),
                "aiAssisted", extractionLog != null
            ),
            ipAddress
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listInvoices(Long authenticatedUserId, InvoiceStatus statusFilter) {
        List<Invoice> invoices = statusFilter != null
            ? invoiceRepository.findByUserIdAndStatusOrderByCreatedAtDesc(authenticatedUserId, statusFilter)
            : invoiceRepository.findByUserIdOrderByCreatedAtDesc(authenticatedUserId);

        return invoices.stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoice(Long authenticatedUserId, Long invoiceId) {
        Invoice invoice = findOwnedInvoice(authenticatedUserId, invoiceId);
        return toResponse(invoice);
    }

    @Transactional
    public InvoiceResponse approveInvoice(
            Long authenticatedUserId,
            Long invoiceId,
            ApproveInvoiceRequest request,
            String ipAddress) {

        return withOwnedInvoiceMutationLease(authenticatedUserId, invoiceId, false, invoice -> {
            if (invoice.getStatus() != InvoiceStatus.DRAFT) {
                throw new InvalidInvoiceStateException(
                    "Only DRAFT invoices can be approved. Current status: " + invoice.getStatus()
                );
            }

            ensureNoProtectedPayments(invoice.getId());
            validateApprovableFields(invoice);

            String currentSnapshotHash = invoiceSnapshotService.computeHash(invoice);
            if (!invoiceSnapshotService.hashesMatch(currentSnapshotHash, request.snapshotHash())) {
                throw new InvalidInvoiceStateException(
                    "Invoice details have changed since review. Refresh the invoice snapshot before approving."
                );
            }

            invoice.setStatus(InvoiceStatus.APPROVED);
            invoice.setApprovedAt(LocalDateTime.now(paymentClock));
            invoice.setApprovedByUserId(authenticatedUserId);
            invoice.setApprovedSnapshotHash(currentSnapshotHash);

            Invoice saved = invoiceRepository.saveAndFlush(invoice);

            auditLogService.logEvent(
                authenticatedUserId,
                "INVOICE",
                saved.getId(),
                "INVOICE_APPROVED",
                AuditActor.USER,
                Map.of(
                    "invoiceNumber", saved.getInvoiceNumber(),
                    "amount", saved.getAmount().toPlainString(),
                    "currency", saved.getCurrency(),
                    "snapshotHash", currentSnapshotHash
                ),
                ipAddress
            );

            return toResponse(saved);
        });
    }

    @Transactional
    public InvoiceResponse revokeApproval(
            Long authenticatedUserId,
            Long invoiceId,
            String ipAddress) {

        return withOwnedInvoiceMutationLease(authenticatedUserId, invoiceId, false, invoice -> {
            if (invoice.getStatus() != InvoiceStatus.APPROVED) {
                throw new InvalidInvoiceStateException(
                    "Only APPROVED invoices can have approval revoked. Current status: " + invoice.getStatus()
                );
            }

            ensureNoProtectedPayments(invoice.getId());

            String previousSnapshotHash = invoice.getApprovedSnapshotHash();
            int invalidatedOrderCount = invalidateUncapturedOrders(
                invoice.getId(), "APPROVAL_REVOKED"
            );
            abortPendingOrderIntents(invoice.getId());
            revokeActivePaymentLinkIfPresent(invoice.getId(), "APPROVAL_REVOKED");

            clearApprovalMetadata(invoice);
            Invoice saved = invoiceRepository.saveAndFlush(invoice);

            auditLogService.logEvent(
                authenticatedUserId,
                "INVOICE",
                saved.getId(),
                "INVOICE_APPROVAL_REVOKED",
                AuditActor.USER,
                Map.of(
                    "invoiceNumber", saved.getInvoiceNumber(),
                    "previousSnapshotHash", previousSnapshotHash != null ? previousSnapshotHash : "",
                    "invalidatedOrderCount", invalidatedOrderCount
                ),
                ipAddress
            );

            return toResponse(saved);
        });
    }

    @Transactional
    public InvoiceResponse updateInvoice(
            Long authenticatedUserId,
            Long invoiceId,
            UpdateInvoiceRequest request,
            String ipAddress) {

        return withOwnedInvoiceMutationLease(authenticatedUserId, invoiceId, false, invoice -> {
            if (invoice.getStatus() != InvoiceStatus.DRAFT && invoice.getStatus() != InvoiceStatus.APPROVED) {
                throw new InvalidInvoiceStateException(
                    "Only DRAFT or APPROVED invoices can be modified. Current status: " + invoice.getStatus()
                );
            }

            ensureNoProtectedPayments(invoice.getId());

            boolean wasApproved = invoice.getStatus() == InvoiceStatus.APPROVED;
            String previousSnapshotHash = invoice.getApprovedSnapshotHash();
            BigDecimal previousAmount = invoice.getAmount();
            String previousCurrency = invoice.getCurrency();
            String normalizedPhone = PhoneNumberValidator.normalizeOptional(request.clientPhone());
            boolean customerSwitched = request.customerId() != null
                && (invoice.getCustomer() == null || !request.customerId().equals(invoice.getCustomer().getId()));

            applyValidatedFields(
                invoice,
                request.clientName(),
                request.clientEmail(),
                normalizedPhone,
                request.description(),
                request.amount(),
                request.currency(),
                request.dueDate()
            );

            if (customerService != null) {
                customerService.resolveAndLinkForInvoice(
                    invoice.getUser(), invoice, request.customerId(), normalizedPhone, customerSwitched
                );
            }

            int invalidatedOrderCount = 0;
            if (wasApproved) {
                invalidatedOrderCount = invalidateUncapturedOrders(
                    invoice.getId(), "INVOICE_EDITED"
                );
                abortPendingOrderIntents(invoice.getId());
                revokeActivePaymentLinkIfPresent(invoice.getId(), "INVOICE_EDITED");
                clearApprovalMetadata(invoice);
            }

            Invoice saved = invoiceRepository.saveAndFlush(invoice);

            auditLogService.logEvent(
                authenticatedUserId,
                "INVOICE",
                saved.getId(),
                "INVOICE_UPDATED",
                AuditActor.USER,
                Map.of(
                    "invoiceNumber", saved.getInvoiceNumber(),
                    "previousAmount", previousAmount.toPlainString(),
                    "previousCurrency", previousCurrency,
                    "newAmount", saved.getAmount().toPlainString(),
                    "newCurrency", saved.getCurrency(),
                    "dueDate", saved.getDueDate().toString()
                ),
                ipAddress
            );

            if (wasApproved) {
                auditLogService.logEvent(
                    authenticatedUserId,
                    "INVOICE",
                    saved.getId(),
                    "INVOICE_APPROVAL_INVALIDATED",
                    AuditActor.USER,
                    Map.of(
                        "invoiceNumber", saved.getInvoiceNumber(),
                        "previousSnapshotHash", previousSnapshotHash != null ? previousSnapshotHash : "",
                        "invalidatedOrderCount", invalidatedOrderCount,
                        "reason", "MATERIAL_INVOICE_EDIT"
                    ),
                    ipAddress
                );
            }

            return toResponse(saved);
        });
    }

    @Transactional
    public void deleteDraftInvoice(Long authenticatedUserId, Long invoiceId, String ipAddress) {
        withOwnedInvoiceMutationLease(authenticatedUserId, invoiceId, true, invoice -> {
            ensureDraft(invoice, "deleted");

            if (!paymentRepository.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).isEmpty()) {
                throw new InvalidInvoiceStateException("Invoices with associated payment records cannot be deleted.");
            }
            if (paymentLinkRepository != null
                    && !paymentLinkRepository.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).isEmpty()) {
                throw new InvalidInvoiceStateException(
                    "Invoices with payment-link history cannot be deleted for audit integrity."
                );
            }
            List<PaymentOrderIntent> intents = paymentOrderIntentRepository
                .findByInvoiceIdOrderByCreatedAtDesc(invoice.getId());
            if (!intents.isEmpty()) {
                if (intents.stream().anyMatch(intent -> intent.getStatus() == PaymentOrderIntentStatus.PENDING)) {
                    throw new InvalidInvoiceStateException("Invoices with active payment intents cannot be deleted.");
                }
                paymentOrderIntentRepository.deleteAllInBatch(intents);
            }

            // Preserve AI transparency log while removing FK link before deleting draft
            List<AiExtractionLog> linkedLogs = extractionLogRepository
                .findByInvoiceIdOrderByCreatedAtDesc(invoice.getId());
            if (!linkedLogs.isEmpty()) {
                linkedLogs.forEach(log -> log.setInvoice(null));
                extractionLogRepository.saveAll(linkedLogs);
            }

            String invoiceNumber = invoice.getInvoiceNumber();
            BigDecimal amount = invoice.getAmount();
            String currency = invoice.getCurrency();

            invoiceRepository.delete(invoice);

            auditLogService.logEvent(
                authenticatedUserId,
                "INVOICE",
                invoiceId,
                "INVOICE_DELETED",
                AuditActor.USER,
                Map.of(
                    "invoiceNumber", invoiceNumber,
                    "amount", amount.toPlainString(),
                    "currency", currency,
                    "previousStatus", InvoiceStatus.DRAFT.name()
                ),
                ipAddress
            );
            return null;
        });
    }

    private <T> T withOwnedInvoiceMutationLease(
            Long authenticatedUserId,
            Long invoiceId,
            boolean deleteOnSuccess,
            Function<Invoice, T> action) {
        String leaseToken = acquireOwnedInvoiceMutationLease(authenticatedUserId, invoiceId);
        boolean completedDelete = false;
        try {
            Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));
            T result = action.apply(invoice);
            completedDelete = deleteOnSuccess;
            return result;
        } finally {
            if (!completedDelete) {
                invoiceRepository.releasePaymentLease(invoiceId, leaseToken);
            }
        }
    }

    private String acquireOwnedInvoiceMutationLease(Long authenticatedUserId, Long invoiceId) {
        Invoice invoice = findOwnedInvoice(authenticatedUserId, invoiceId);
        LocalDateTime now = LocalDateTime.now(paymentClock);
        String leaseToken = UUID.randomUUID().toString();
        int acquired = invoiceRepository.tryAcquirePaymentLease(
            invoice.getId(), leaseToken, now, now.plusSeconds(MUTATION_LEASE_SECONDS)
        );
        if (acquired != 1) {
            throw new InvalidInvoiceStateException(
                "Cannot modify or approve invoice while another payment operation is in progress."
            );
        }
        return leaseToken;
    }

    private Invoice findOwnedInvoice(Long authenticatedUserId, Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));

        if (!invoice.getUser().getId().equals(authenticatedUserId)) {
            throw new ForbiddenOperationException("You do not have permission to access this invoice.");
        }
        return invoice;
    }

    private void ensureDraft(Invoice invoice, String operation) {
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvalidInvoiceStateException(
                "Only DRAFT invoices can be " + operation + ". Current status: " + invoice.getStatus()
            );
        }
    }

    private void ensureNoProtectedPayments(Long invoiceId) {
        if (paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.CAPTURED)
                || paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.PENDING)
                || paymentRepository.existsByInvoiceIdAndCaptureRequestedAtIsNotNullAndStatusIn(
                    invoiceId, UNCAPTURED_ORDER_STATUSES)) {
            throw new InvalidInvoiceStateException(
                "Invoices with pending, ambiguous, or captured payments cannot be modified, approved, or revoked."
            );
        }
    }

    private void validateApprovableFields(Invoice invoice) {
        Set<ConstraintViolation<Invoice>> violations = validator.validate(invoice);
        if (!violations.isEmpty()) {
            String message = violations.iterator().next().getMessage();
            throw new InvalidInvoiceStateException("Invoice is missing valid required fields: " + message);
        }
        if (invoice.getDescription() == null || invoice.getDescription().isBlank()) {
            throw new InvalidInvoiceStateException("Invoice description is required before approval.");
        }
        if (invoice.getCurrency() == null || !invoice.getCurrency().matches("^[A-Z]{3}$")) {
            throw new InvalidInvoiceStateException("Invoice currency must be a 3-letter ISO code before approval.");
        }
        if (invoice.getDueDate() == null || invoice.getDueDate().isBefore(LocalDate.now(paymentClock))) {
            throw new InvalidInvoiceStateException("Invoice due date must be today or in the future before approval.");
        }
    }

    /**
     * Marks uncaptured orders (CREATED / APPROVED) unusable locally without calling PayPal.
     * PENDING and CAPTURED payments are never touched here.
     */
    private int invalidateUncapturedOrders(Long invoiceId, String reason) {
        List<Payment> uncaptured = paymentRepository.findByInvoiceIdAndStatusIn(invoiceId, UNCAPTURED_ORDER_STATUSES);
        if (uncaptured.isEmpty()) {
            return 0;
        }
        for (Payment payment : uncaptured) {
            payment.setStatus(PaymentStatus.CANCELLED);
            Map<String, Object> summary = payment.getPaypalResponseSummary() != null
                ? new LinkedHashMap<>(payment.getPaypalResponseSummary())
                : new LinkedHashMap<>();
            summary.put("localInvalidated", true);
            summary.put("localInvalidationReason", reason);
            summary.put("localInvalidatedAt", LocalDateTime.now(paymentClock).toString());
            payment.setPaypalResponseSummary(summary);
        }
        paymentRepository.saveAllAndFlush(uncaptured);
        return uncaptured.size();
    }

    private void abortPendingOrderIntents(Long invoiceId) {
        List<PaymentOrderIntent> pendingIntents = paymentOrderIntentRepository
            .findByInvoiceIdAndStatus(invoiceId, PaymentOrderIntentStatus.PENDING);
        if (pendingIntents.isEmpty()) {
            return;
        }
        for (PaymentOrderIntent intent : pendingIntents) {
            intent.setStatus(PaymentOrderIntentStatus.ABORTED);
        }
        paymentOrderIntentRepository.saveAllAndFlush(pendingIntents);
    }

    private void clearApprovalMetadata(Invoice invoice) {
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setApprovedAt(null);
        invoice.setApprovedByUserId(null);
        invoice.setApprovedSnapshotHash(null);
    }

    private void revokeActivePaymentLinkIfPresent(Long invoiceId, String reason) {
        if (paymentLinkRepository == null) {
            return;
        }
        paymentLinkRepository.findByActiveInvoiceId(invoiceId).ifPresent(link -> {
            link.revoke(LocalDateTime.now(paymentClock), reason);
            paymentLinkRepository.saveAndFlush(link);
        });
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        return InvoiceResponse.from(invoice, invoiceSnapshotService.computeHash(invoice));
    }

    private void applyValidatedFields(
            Invoice invoice,
            String clientName,
            String clientEmail,
            String normalizedClientPhone,
            String description,
            BigDecimal amount,
            String currency,
            LocalDate dueDate) {

        invoice.setClientName(clientName.trim());
        invoice.setClientEmail(clientEmail.trim().toLowerCase(Locale.ROOT));
        invoice.setClientPhone(normalizedClientPhone);
        invoice.setDescription(description.trim());
        invoice.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        invoice.setCurrency(currency.trim().toUpperCase(Locale.ROOT));
        invoice.setDueDate(dueDate);
    }

    private String generateUniqueInvoiceNumber(Long userId) {
        String datePart = LocalDate.now(paymentClock).format(DATE_PREFIX_FORMAT);
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            StringBuilder suffix = new StringBuilder(INVOICE_CODE_LENGTH);
            for (int i = 0; i < INVOICE_CODE_LENGTH; i++) {
                suffix.append(INVOICE_CODE_CHARS.charAt(SECURE_RANDOM.nextInt(INVOICE_CODE_CHARS.length())));
            }
            String candidate = "INV-" + datePart + "-" + suffix;
            if (!invoiceRepository.existsByInvoiceNumberAndUserId(candidate, userId)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique invoice number. Please retry.");
    }
}

