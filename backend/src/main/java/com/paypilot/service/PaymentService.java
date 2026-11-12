package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.payment.CancelPaymentRequest;
import com.paypilot.dto.payment.CreatePaymentOrderRequest;
import com.paypilot.dto.payment.PaymentResponse;
import com.paypilot.dto.paypal.PayPalCaptureResult;
import com.paypilot.dto.paypal.PayPalCreateOrderRequest;
import com.paypilot.dto.paypal.PayPalOrderDetailsResult;
import com.paypilot.dto.paypal.PayPalOrderResult;
import com.paypilot.dto.publicpay.PublicPaymentInvoiceResponse;
import com.paypilot.dto.publicpay.PublicPaymentOrderResponse;
import com.paypilot.exception.ForbiddenOperationException;
import com.paypilot.exception.InvalidInvoiceStateException;
import com.paypilot.exception.PayPalApiException;
import com.paypilot.exception.PayPalIntegrationException;
import com.paypilot.exception.PaymentDeclinedException;
import com.paypilot.exception.PaymentLinkGoneException;
import com.paypilot.exception.PaymentVerificationException;
import com.paypilot.exception.ResourceNotFoundException;
import com.paypilot.model.AuditActor;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentLink;
import com.paypilot.model.PaymentOrderIntent;
import com.paypilot.model.PaymentOrderIntentStatus;
import com.paypilot.model.PaymentStatus;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentOrderIntentRepository;
import com.paypilot.repository.PaymentRepository;
import com.paypilot.service.PaymentLinkService.ValidatedLinkContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Authoritative payment orchestration service coordinating explicit invoice approval,
 * snapshot hash verification, atomic cross-instance DB leases, durable order intents,
 * PayPal Sandbox order creation/capture/recovery, local cancellation, and audit logging.
 *
 * Serves both authenticated Seller Portal requests and token-authorized Customer Portal
 * public payment-link requests through a single shared verification pipeline.
 *
 * Transaction boundary guarantee:
 *   Database transactions are opened only for short atomic pre-checks and post-verification commits.
 *   PayPal HTTP calls always execute strictly outside active database transactions.
 */
@Slf4j
@Service
public class PaymentService {

    private static final int LOCK_STRIPE_COUNT = 256;
    private static final List<PaymentStatus> ACTIVE_UNCAPTURED_STATUSES =
        List.of(PaymentStatus.CREATED, PaymentStatus.APPROVED);
    private static final List<PaymentStatus> INACTIVE_ATTEMPT_STATUSES =
        List.of(PaymentStatus.CANCELLED, PaymentStatus.FAILED);
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern ISO_CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentOrderIntentRepository paymentOrderIntentRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentLinkService paymentLinkService;
    private final InvoiceLeaseService invoiceLeaseService;
    private final InvoiceSnapshotService invoiceSnapshotService;
    private final PayPalSandboxClient payPalSandboxClient;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final long approvalMaxAgeHours;
    private final String defaultFrontendOrigin;
    private final Set<String> allowedFrontendOriginKeys;
    private final ReentrantLock[] invoiceLockStripes;

    public PaymentService(
            InvoiceRepository invoiceRepository,
            PaymentRepository paymentRepository,
            PaymentOrderIntentRepository paymentOrderIntentRepository,
            PaymentLinkRepository paymentLinkRepository,
            PaymentLinkService paymentLinkService,
            InvoiceLeaseService invoiceLeaseService,
            InvoiceSnapshotService invoiceSnapshotService,
            PayPalSandboxClient payPalSandboxClient,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager,
            @Qualifier("paymentClock") Clock clock,
            @Value("${payment.approval-max-age-hours:168}") long approvalMaxAgeHours,
            @Value("${cors.allowed-origins:http://localhost:5173}") String allowedOriginsRaw) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentOrderIntentRepository = paymentOrderIntentRepository;
        this.paymentLinkRepository = paymentLinkRepository;
        this.paymentLinkService = paymentLinkService;
        this.invoiceLeaseService = invoiceLeaseService;
        this.invoiceSnapshotService = invoiceSnapshotService;
        this.payPalSandboxClient = payPalSandboxClient;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.approvalMaxAgeHours = Math.max(1L, approvalMaxAgeHours);
        this.defaultFrontendOrigin = resolveDefaultOrigin(allowedOriginsRaw);
        this.allowedFrontendOriginKeys = parseAllowedOriginKeys(allowedOriginsRaw, this.defaultFrontendOrigin);
        this.invoiceLockStripes = new ReentrantLock[LOCK_STRIPE_COUNT];
        for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
            this.invoiceLockStripes[i] = new ReentrantLock();
        }
    }

    // -------------------------------------------------------------------------
    // Seller Portal (JWT-authenticated) entrypoints
    // -------------------------------------------------------------------------

    public PaymentOrderOutcome createOrder(
            Long authenticatedUserId,
            CreatePaymentOrderRequest request,
            String ipAddress) {
        if (request == null || request.invoiceId() == null) {
            throw new IllegalArgumentException("Invoice ID is required.");
        }
        Long invoiceId = request.invoiceId();
        String returnUrl = resolveRedirectUrl(request.returnUrl(), invoiceId, "return");
        String cancelUrl = resolveRedirectUrl(request.cancelUrl(), invoiceId, "cancel");

        verifyInvoiceOwnershipBeforeLease(authenticatedUserId, invoiceId, ipAddress);

        return executeCreateOrder(
            CheckoutAuthority.seller(authenticatedUserId),
            invoiceId,
            returnUrl,
            cancelUrl,
            ipAddress
        );
    }

    public PaymentResponse captureOrder(
            Long authenticatedUserId,
            String rawOrderId,
            String ipAddress) {
        String orderId = validateOrderId(rawOrderId);
        OwnedPaymentSummary ownership = resolveOwnedPaymentSummary(authenticatedUserId, orderId, ipAddress);

        return executeCaptureOrder(
            CheckoutAuthority.seller(authenticatedUserId),
            ownership.invoiceId(),
            ownership.paymentId(),
            orderId,
            ipAddress
        );
    }

    public PaymentResponse cancelOrder(
            Long authenticatedUserId,
            String rawOrderId,
            CancelPaymentRequest request,
            String ipAddress) {
        String orderId = validateOrderId(rawOrderId);
        OwnedPaymentSummary ownership = resolveOwnedPaymentSummary(authenticatedUserId, orderId, ipAddress);
        String reason = request != null && request.reason() != null && !request.reason().isBlank()
            ? request.reason().trim()
            : null;

        return executeCancelOrder(
            CheckoutAuthority.seller(authenticatedUserId),
            ownership.invoiceId(),
            orderId,
            reason,
            ipAddress
        );
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForInvoice(Long authenticatedUserId, Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));

        if (!invoice.getUser().getId().equals(authenticatedUserId)) {
            throw new ForbiddenOperationException("You do not have permission to view payments for this invoice.");
        }

        return paymentRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
            .map(payment -> PaymentResponse.from(payment, invoice, false))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listPaymentsForSeller(Long authenticatedUserId, PaymentStatus statusFilter) {
        List<Payment> payments = statusFilter != null
            ? paymentRepository.findAllForSellerAndStatusOrderByCreatedAtDesc(authenticatedUserId, statusFilter)
            : paymentRepository.findAllForSellerOrderByCreatedAtDesc(authenticatedUserId);
        return payments.stream()
            .map(payment -> PaymentResponse.from(payment, payment.getInvoice(), false))
            .toList();
    }

    // -------------------------------------------------------------------------
    // Customer Portal (Public payment-link token-authenticated) entrypoints
    // -------------------------------------------------------------------------

    public PublicPaymentOrderOutcome createOrderForPaymentLink(
            String rawToken,
            String requestedReturnUrl,
            String requestedCancelUrl,
            String ipAddress) {
        ValidatedLinkContext linkCtx = paymentLinkService.resolveLinkContextForCheckout(rawToken, false);
        String returnUrl = resolveRedirectUrlForPaymentLink(requestedReturnUrl, rawToken, "return");
        String cancelUrl = resolveRedirectUrlForPaymentLink(requestedCancelUrl, rawToken, "cancel");

        PaymentOrderOutcome outcome = executeCreateOrder(
            CheckoutAuthority.customerLink(linkCtx.sellerUserId(), linkCtx.paymentLinkId(), linkCtx.linkSnapshotHash()),
            linkCtx.invoiceId(),
            returnUrl,
            cancelUrl,
            ipAddress
        );

        PaymentResponse r = outcome.response();
        PublicPaymentOrderResponse publicOrder = new PublicPaymentOrderResponse(
            r.paypalOrderId(),
            r.status(),
            r.amount(),
            r.currency(),
            r.approvalUrl(),
            outcome.reused(),
            true
        );
        return new PublicPaymentOrderOutcome(publicOrder, outcome.reused());
    }

    public PublicCaptureOutcome captureOrderForPaymentLink(
            String rawToken,
            String rawOrderId,
            String ipAddress) {
        String orderId = validateOrderId(rawOrderId);
        ValidatedLinkContext linkCtx = paymentLinkService.resolveLinkContextForCheckout(rawToken, true);
        PaymentSummaryBound paymentSummary = resolveLinkBoundPaymentSummary(linkCtx, orderId, ipAddress);

        PaymentResponse captured = executeCaptureOrder(
            CheckoutAuthority.customerLink(linkCtx.sellerUserId(), linkCtx.paymentLinkId(), linkCtx.linkSnapshotHash()),
            linkCtx.invoiceId(),
            paymentSummary.paymentId(),
            orderId,
            ipAddress
        );

        PublicPaymentInvoiceResponse view = paymentLinkService.buildPublicPaymentView(rawToken, false);
        return new PublicCaptureOutcome(view, captured.pending());
    }

    public PublicPaymentInvoiceResponse cancelOrderForPaymentLink(
            String rawToken,
            String rawOrderId,
            String rawReason,
            String ipAddress) {
        ValidatedLinkContext linkCtx = paymentLinkService.resolveLinkContextForCheckout(rawToken, false);
        String reason = rawReason != null && !rawReason.isBlank()
            ? rawReason.trim()
            : "Checkout cancelled by customer on PayPal Sandbox";

        String orderIdToCancel;
        if (rawOrderId != null && !rawOrderId.isBlank()) {
            String validated = validateOrderId(rawOrderId);
            resolveLinkBoundPaymentSummary(linkCtx, validated, ipAddress);
            orderIdToCancel = validated;
        } else {
            orderIdToCancel = resolveActiveOrderIdForLink(linkCtx);
        }

        if (orderIdToCancel != null) {
            executeCancelOrder(
                CheckoutAuthority.customerLink(linkCtx.sellerUserId(), linkCtx.paymentLinkId(), linkCtx.linkSnapshotHash()),
                linkCtx.invoiceId(),
                orderIdToCancel,
                reason,
                ipAddress
            );
        }

        return paymentLinkService.buildPublicPaymentView(rawToken, false);
    }

    // -------------------------------------------------------------------------
    // Shared core PayPal Sandbox orchestration pipeline
    // -------------------------------------------------------------------------

    private PaymentOrderOutcome executeCreateOrder(
            CheckoutAuthority authority,
            Long invoiceId,
            String returnUrl,
            String cancelUrl,
            String ipAddress) {
        ReentrantLock localLock = lockForInvoice(invoiceId);
        localLock.lock();
        String leaseToken = null;
        try {
            leaseToken = invoiceLeaseService.acquireLease(invoiceId);

            OrderPreparationStep preCheck = transactionTemplate.execute(
                status -> prepareOrderCreation(authority, invoiceId, returnUrl, cancelUrl, ipAddress)
            );
            if (preCheck == null) {
                throw new IllegalStateException("Unable to prepare PayPal order creation.");
            }
            if (preCheck.outcome() != null) {
                return preCheck.outcome();
            }

            PayPalOrderResult paypalOrder;
            try {
                paypalOrder = payPalSandboxClient.createOrder(preCheck.paypalRequest());
            } catch (PayPalIntegrationException ex) {
                Map<String, Object> details = safeFailureDetails(invoiceId, null, preCheck.snapshotHash(), ex.getMessage());
                enrichAuthorityAuditDetails(details, authority);
                auditLogService.logIndependentEvent(
                    authority.sellerUserId(),
                    "INVOICE",
                    invoiceId,
                    "PAYMENT_ORDER_CREATION_FAILED",
                    authority.auditActor(),
                    details,
                    ipAddress
                );
                throw ex;
            }

            return persistCreatedOrderWithRaceRecovery(authority, preCheck, paypalOrder, ipAddress);
        } finally {
            invoiceLeaseService.releaseLease(invoiceId, leaseToken);
            localLock.unlock();
        }
    }

    private PaymentResponse executeCaptureOrder(
            CheckoutAuthority authority,
            Long invoiceId,
            Long paymentId,
            String orderId,
            String ipAddress) {
        ReentrantLock localLock = lockForInvoice(invoiceId);
        localLock.lock();
        String leaseToken = null;
        try {
            leaseToken = invoiceLeaseService.acquireLease(invoiceId);

            Map<String, Object> attemptDetails = new LinkedHashMap<>();
            attemptDetails.put("invoiceId", invoiceId);
            attemptDetails.put("paypalOrderId", orderId);
            enrichAuthorityAuditDetails(attemptDetails, authority);

            auditLogService.logIndependentEvent(
                authority.sellerUserId(),
                "PAYMENT",
                paymentId,
                "PAYMENT_CAPTURE_ATTEMPTED",
                authority.auditActor(),
                attemptDetails,
                ipAddress
            );

            CapturePreCheckResult preCheck = transactionTemplate.execute(
                status -> prepareCaptureExecution(authority, invoiceId, orderId, ipAddress)
            );
            if (preCheck == null) {
                throw new IllegalStateException("Unable to prepare PayPal capture verification.");
            }
            if (preCheck.immediateResponse() != null) {
                return preCheck.immediateResponse();
            }
            if (preCheck.conflictMessage() != null) {
                throw new InvalidInvoiceStateException(preCheck.conflictMessage());
            }

            CaptureRemoteOutcome remoteOutcome = executeRemotePayPalVerification(
                authority, paymentId, preCheck, orderId, ipAddress
            );

            CaptureFinalizeResult finalizeResult = transactionTemplate.execute(
                status -> finalizeCaptureVerification(authority, invoiceId, orderId, remoteOutcome, ipAddress)
            );
            if (finalizeResult == null) {
                throw new IllegalStateException("Unable to finalize PayPal capture verification.");
            }
            if (finalizeResult.conflictMessage() != null) {
                throw new InvalidInvoiceStateException(finalizeResult.conflictMessage());
            }
            if (finalizeResult.verificationError() != null) {
                throw new PaymentVerificationException(finalizeResult.verificationError());
            }
            if (finalizeResult.declinedError() != null) {
                throw new PaymentDeclinedException(finalizeResult.declinedError());
            }
            return finalizeResult.response();
        } finally {
            invoiceLeaseService.releaseLease(invoiceId, leaseToken);
            localLock.unlock();
        }
    }

    private PaymentResponse executeCancelOrder(
            CheckoutAuthority authority,
            Long invoiceId,
            String orderId,
            String reason,
            String ipAddress) {
        ReentrantLock localLock = lockForInvoice(invoiceId);
        localLock.lock();
        String leaseToken = null;
        try {
            leaseToken = invoiceLeaseService.acquireLease(invoiceId);

            CancelOutcome outcome = transactionTemplate.execute(
                status -> performLocalCancel(authority, invoiceId, orderId, reason, ipAddress)
            );
            if (outcome == null) {
                throw new IllegalStateException("Unable to process payment cancellation.");
            }
            if (outcome.conflictMessage() != null) {
                throw new InvalidInvoiceStateException(outcome.conflictMessage());
            }
            return outcome.response();
        } finally {
            invoiceLeaseService.releaseLease(invoiceId, leaseToken);
            localLock.unlock();
        }
    }

    private OrderPreparationStep prepareOrderCreation(
            CheckoutAuthority authority,
            Long invoiceId,
            String returnUrl,
            String cancelUrl,
            String ipAddress) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));

        if (!invoice.getUser().getId().equals(authority.sellerUserId())) {
            auditVerificationFailure(authority, "INVOICE", invoiceId, null,
                "Ownership violation when attempting PayPal order creation", ipAddress);
            throw new ForbiddenOperationException("You do not have permission to initiate payment for this invoice.");
        }

        if (authority.isCustomerLink()) {
            ensureLinkCurrentlyUsableInTx(authority.paymentLinkId());
        }

        Optional<String> validationFailure = evaluateApprovalValidity(
            invoice, authority.sellerUserId(), authority.expectedLinkSnapshotHash()
        );
        if (validationFailure.isPresent()) {
            abortPendingIntents(invoiceId);
            auditVerificationFailure(authority, "INVOICE", invoiceId,
                invoice.getApprovedSnapshotHash(), validationFailure.get(), ipAddress);
            throw new InvalidInvoiceStateException(validationFailure.get());
        }

        if (paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.CAPTURED)) {
            abortPendingIntents(invoiceId);
            auditVerificationFailure(authority, "INVOICE", invoiceId,
                invoice.getApprovedSnapshotHash(), "Invoice already has a captured payment.", ipAddress);
            throw new InvalidInvoiceStateException("Invoice has already been paid.");
        }

        if (paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.PENDING)) {
            auditVerificationFailure(authority, "INVOICE", invoiceId,
                invoice.getApprovedSnapshotHash(), "Invoice already has a pending payment.", ipAddress);
            throw new InvalidInvoiceStateException("Invoice already has a pending PayPal payment awaiting confirmation.");
        }

        String currentHash = invoiceSnapshotService.computeHash(invoice);
        invalidateStaleActiveOrders(invoice, currentHash, authority.paymentLinkId());

        Optional<Payment> reusableOrder = paymentRepository
            .findFirstByInvoiceIdAndInvoiceSnapshotHashAndStatusInOrderByCreatedAtDesc(
                invoiceId, currentHash, ACTIVE_UNCAPTURED_STATUSES
            );
        if (reusableOrder.isPresent()
                && isValidReusableOrder(reusableOrder.get(), invoice)
                && matchesAuthorityLink(reusableOrder.get(), authority)) {
            Payment existing = reusableOrder.get();
            Map<String, Object> auditDetails = new LinkedHashMap<>();
            auditDetails.put("invoiceId", invoiceId);
            auditDetails.put("paypalOrderId", existing.getPaypalOrderId());
            auditDetails.put("snapshotHash", currentHash);
            enrichAuthorityAuditDetails(auditDetails, authority);

            auditLogService.logEvent(
                authority.sellerUserId(),
                "PAYMENT",
                existing.getId(),
                "PAYMENT_ORDER_REUSED",
                authority.auditActor(),
                auditDetails,
                ipAddress
            );
            return new OrderPreparationStep(
                invoiceId,
                null,
                new PaymentOrderOutcome(PaymentResponse.from(existing, invoice, true), true),
                null,
                currentHash,
                null
            );
        }

        BigDecimal normalizedAmount = invoice.getAmount().setScale(2, RoundingMode.HALF_UP);
        String normalizedCurrency = invoice.getCurrency().trim().toUpperCase(Locale.ROOT);
        PaymentOrderIntent intent = resolveOrCreateOrderIntent(invoice, currentHash, normalizedAmount, normalizedCurrency);

        PayPalCreateOrderRequest paypalRequest = new PayPalCreateOrderRequest(
            intent.getIdempotencyKey(),
            invoice.getInvoiceNumber(),
            "inv-" + invoiceId + "-" + currentHash.substring(0, 12),
            invoice.getDescription(),
            normalizedAmount,
            normalizedCurrency,
            returnUrl,
            cancelUrl
        );

        return new OrderPreparationStep(
            invoiceId,
            intent.getId(),
            null,
            paypalRequest,
            currentHash,
            intent.getIdempotencyKey()
        );
    }

    private PaymentOrderIntent resolveOrCreateOrderIntent(
            Invoice invoice,
            String currentHash,
            BigDecimal amount,
            String currency) {
        Long invoiceId = invoice.getId();
        Optional<PaymentOrderIntent> pendingOpt = paymentOrderIntentRepository
            .findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(invoiceId, PaymentOrderIntentStatus.PENDING);

        if (pendingOpt.isPresent()) {
            PaymentOrderIntent pending = pendingOpt.get();
            boolean sameSnapshot = invoiceSnapshotService.hashesMatch(currentHash, pending.getInvoiceSnapshotHash())
                && pending.getAmount() != null && pending.getAmount().compareTo(amount) == 0
                && pending.getCurrency() != null && pending.getCurrency().equalsIgnoreCase(currency);
            if (sameSnapshot) {
                return pending;
            }
            pending.setStatus(PaymentOrderIntentStatus.ABORTED);
            paymentOrderIntentRepository.saveAndFlush(pending);
        }

        int maxIntentAttempt = paymentOrderIntentRepository.findMaxAttemptNumber(invoiceId, currentHash);
        long inactivePaymentCount = paymentRepository.countByInvoiceIdAndInvoiceSnapshotHashAndStatusIn(
            invoiceId, currentHash, INACTIVE_ATTEMPT_STATUSES
        );
        int nextAttempt = Math.max(maxIntentAttempt + 1, (int) inactivePaymentCount);
        String idempotencyKey = buildOrderIdempotencyKey(invoiceId, currentHash, nextAttempt);

        PaymentOrderIntent created = PaymentOrderIntent.builder()
            .invoice(invoice)
            .invoiceSnapshotHash(currentHash)
            .idempotencyKey(idempotencyKey)
            .attemptNumber(nextAttempt)
            .amount(amount)
            .currency(currency)
            .status(PaymentOrderIntentStatus.PENDING)
            .build();
        return paymentOrderIntentRepository.saveAndFlush(created);
    }

    private PaymentOrderOutcome persistCreatedOrderWithRaceRecovery(
            CheckoutAuthority authority,
            OrderPreparationStep preCheck,
            PayPalOrderResult paypalOrder,
            String ipAddress) {
        try {
            OrderPersistOutcome persistOutcome = transactionTemplate.execute(
                status -> persistCreatedOrder(authority, preCheck, paypalOrder, ipAddress)
            );
            if (persistOutcome == null) {
                throw new IllegalStateException("Unable to persist PayPal order.");
            }
            if (persistOutcome.conflictMessage() != null) {
                throw new InvalidInvoiceStateException(persistOutcome.conflictMessage());
            }
            return persistOutcome.outcome();
        } catch (DataIntegrityViolationException ex) {
            PaymentOrderOutcome recovered = transactionTemplate.execute(status -> {
                Optional<Payment> existing = paymentRepository.findByPaypalOrderId(paypalOrder.paypalOrderId());
                if (existing.isPresent()) {
                    Payment p = existing.get();
                    Invoice inv = p.getInvoice();
                    if (inv.getId().equals(preCheck.invoiceId())
                            && inv.getUser().getId().equals(authority.sellerUserId())
                            && matchesAuthorityLink(p, authority)
                            && invoiceSnapshotService.hashesMatch(preCheck.snapshotHash(), p.getInvoiceSnapshotHash())
                            && ACTIVE_UNCAPTURED_STATUSES.contains(p.getStatus())) {
                        markIntentCompleted(preCheck.intentId(), p.getPaypalOrderId());
                        return new PaymentOrderOutcome(PaymentResponse.from(p, inv, true), true);
                    }
                }
                return null;
            });
            if (recovered != null) {
                return recovered;
            }
            throw ex;
        }
    }

    private OrderPersistOutcome persistCreatedOrder(
            CheckoutAuthority authority,
            OrderPreparationStep preCheck,
            PayPalOrderResult paypalOrder,
            String ipAddress) {
        Long invoiceId = preCheck.invoiceId();
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));

        boolean linkInvalidated = authority.isCustomerLink() && !isLinkCurrentlyUsableInTx(authority.paymentLinkId());
        Optional<String> validationFailure = evaluateApprovalValidity(
            invoice, authority.sellerUserId(), authority.expectedLinkSnapshotHash()
        );
        if (linkInvalidated
                || validationFailure.isPresent()
                || !invoiceSnapshotService.hashesMatch(preCheck.snapshotHash(), invoiceSnapshotService.computeHash(invoice))) {
            markIntentAborted(preCheck.intentId());

            Optional<Payment> alreadySaved = paymentRepository.findByPaypalOrderId(paypalOrder.paypalOrderId());
            if (alreadySaved.isEmpty()) {
                PaymentLink linkRef = authority.isCustomerLink()
                    ? paymentLinkRepository.getReferenceById(authority.paymentLinkId())
                    : null;
                Payment invalidated = Payment.builder()
                    .invoice(invoice)
                    .paymentLink(linkRef)
                    .paypalOrderId(paypalOrder.paypalOrderId())
                    .invoiceSnapshotHash(preCheck.snapshotHash())
                    .idempotencyKey(preCheck.idempotencyKey())
                    .paypalApprovalUrl(paypalOrder.approvalUrl())
                    .amount(preCheck.paypalRequest().amount())
                    .currency(preCheck.paypalRequest().currency())
                    .status(PaymentStatus.CANCELLED)
                    .paypalResponseSummary(mergeSummary(paypalOrder.summary(), Map.of(
                        "localInvalidated", true,
                        "localInvalidationReason", linkInvalidated
                            ? "PAYMENT_LINK_REVOKED_OR_EXPIRED_DURING_ORDER_CREATION"
                            : "APPROVAL_INVALIDATED_DURING_ORDER_CREATION"
                    )))
                    .build();
                paymentRepository.saveAndFlush(invalidated);
            }

            String reasonMsg = linkInvalidated
                ? "Payment link was revoked or expired while creating PayPal order."
                : "Invoice approval was modified or revoked while creating PayPal order.";
            auditVerificationFailure(authority, "INVOICE", invoiceId, preCheck.snapshotHash(), reasonMsg, ipAddress);
            if (linkInvalidated) {
                throw new PaymentLinkGoneException("This payment link has been revoked or expired.");
            }
            return new OrderPersistOutcome(null,
                "Invoice approval is no longer valid. Please review and re-approve before paying.");
        }

        if (paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.CAPTURED)
                || paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.PENDING)) {
            markIntentAborted(preCheck.intentId());
            return new OrderPersistOutcome(null, "Invoice already has a pending or captured payment.");
        }

        Optional<Payment> existingActive = paymentRepository
            .findFirstByInvoiceIdAndInvoiceSnapshotHashAndStatusInOrderByCreatedAtDesc(
                invoiceId, preCheck.snapshotHash(), ACTIVE_UNCAPTURED_STATUSES
            );
        if (existingActive.isPresent()
                && isValidReusableOrder(existingActive.get(), invoice)
                && matchesAuthorityLink(existingActive.get(), authority)) {
            Payment existing = existingActive.get();
            markIntentCompleted(preCheck.intentId(), existing.getPaypalOrderId());
            return new OrderPersistOutcome(
                new PaymentOrderOutcome(PaymentResponse.from(existing, invoice, true), true),
                null
            );
        }

        PaymentLink linkRef = authority.isCustomerLink()
            ? paymentLinkRepository.getReferenceById(authority.paymentLinkId())
            : null;

        Payment payment = Payment.builder()
            .invoice(invoice)
            .paymentLink(linkRef)
            .paypalOrderId(paypalOrder.paypalOrderId())
            .invoiceSnapshotHash(preCheck.snapshotHash())
            .idempotencyKey(preCheck.idempotencyKey())
            .paypalApprovalUrl(paypalOrder.approvalUrl())
            .amount(preCheck.paypalRequest().amount())
            .currency(preCheck.paypalRequest().currency())
            .status(PaymentStatus.CREATED)
            .paypalResponseSummary(new LinkedHashMap<>(paypalOrder.summary()))
            .build();

        Payment saved = paymentRepository.saveAndFlush(payment);
        markIntentCompleted(preCheck.intentId(), saved.getPaypalOrderId());

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("invoiceId", invoiceId);
        auditDetails.put("invoiceNumber", invoice.getInvoiceNumber());
        auditDetails.put("paypalOrderId", saved.getPaypalOrderId());
        auditDetails.put("amount", saved.getAmount().toPlainString());
        auditDetails.put("currency", saved.getCurrency());
        auditDetails.put("snapshotHash", preCheck.snapshotHash());
        auditDetails.put("idempotencyKey", preCheck.idempotencyKey());
        enrichAuthorityAuditDetails(auditDetails, authority);

        auditLogService.logEvent(
            authority.sellerUserId(),
            "PAYMENT",
            saved.getId(),
            "PAYMENT_ORDER_CREATED",
            authority.auditActor(),
            auditDetails,
            ipAddress
        );

        return new OrderPersistOutcome(
            new PaymentOrderOutcome(PaymentResponse.from(saved, invoice, false), false),
            null
        );
    }

    private CapturePreCheckResult prepareCaptureExecution(
            CheckoutAuthority authority,
            Long invoiceId,
            String orderId,
            String ipAddress) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));
        Payment payment = paymentRepository.findByPaypalOrderIdForUpdate(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment order not found."));

        if (!invoice.getUser().getId().equals(authority.sellerUserId())
                || !payment.getInvoice().getId().equals(invoiceId)
                || !matchesAuthorityLink(payment, authority)) {
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(), "Ownership or payment-link binding violation on capture attempt.", ipAddress);
            throw new ForbiddenOperationException("You do not have permission to capture this payment.");
        }

        if (authority.isCustomerLink()) {
            PaymentLink link = paymentLinkRepository.findById(authority.paymentLinkId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment link not found."));
            if (link.isRevoked()) {
                auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                    payment.getInvoiceSnapshotHash(), "Capture blocked because payment link was revoked.", ipAddress);
                throw new PaymentLinkGoneException("This payment link has been revoked and can no longer be used.");
            }
            if (payment.getStatus() == PaymentStatus.CAPTURED) {
                if (invoice.getStatus() != InvoiceStatus.PAID) {
                    auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                        payment.getInvoiceSnapshotHash(),
                        "Payment is CAPTURED but invoice status is not PAID.", ipAddress);
                    return CapturePreCheckResult.conflict("Invoice payment state requires reconciliation.");
                }
                return idempotentCaptureReplay(authority, invoiceId, orderId, payment, invoice, ipAddress);
            }
            if (link.isExpired(LocalDateTime.now(clock))) {
                auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                    payment.getInvoiceSnapshotHash(), "Capture blocked because payment link expired.", ipAddress);
                throw new PaymentLinkGoneException("This payment link has expired. Please request a new link from the seller.");
            }
        } else if (payment.getStatus() == PaymentStatus.CAPTURED) {
            if (invoice.getStatus() != InvoiceStatus.PAID) {
                auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                    payment.getInvoiceSnapshotHash(),
                    "Payment is CAPTURED but invoice status is not PAID.", ipAddress);
                return CapturePreCheckResult.conflict("Invoice payment state requires reconciliation.");
            }
            return idempotentCaptureReplay(authority, invoiceId, orderId, payment, invoice, ipAddress);
        }

        if (payment.getStatus() == PaymentStatus.CANCELLED || payment.getStatus() == PaymentStatus.FAILED) {
            String message = payment.getStatus() == PaymentStatus.CANCELLED
                ? "Cancelled or invalidated payment orders cannot be captured."
                : "Failed payment orders cannot be captured. Create a new order after reviewing the invoice.";
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(), message, ipAddress);
            return CapturePreCheckResult.conflict(message);
        }

        String expectedSnapshot = authority.expectedLinkSnapshotHash() != null
            ? authority.expectedLinkSnapshotHash()
            : payment.getInvoiceSnapshotHash();
        Optional<String> validationFailure = evaluateApprovalValidity(
            invoice, authority.sellerUserId(), expectedSnapshot
        );
        if (validationFailure.isEmpty() && authority.expectedLinkSnapshotHash() != null
                && !invoiceSnapshotService.hashesMatch(authority.expectedLinkSnapshotHash(), payment.getInvoiceSnapshotHash())) {
            validationFailure = Optional.of("Payment order snapshot no longer matches the payment link snapshot.");
        }

        if (validationFailure.isPresent()) {
            if (ACTIVE_UNCAPTURED_STATUSES.contains(payment.getStatus()) && payment.getCaptureRequestedAt() == null) {
                markPaymentLocallyInvalidated(payment, "CAPTURE_BLOCKED_APPROVAL_OR_SNAPSHOT_INVALID");
            }
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(), validationFailure.get(), ipAddress);
            return CapturePreCheckResult.conflict(validationFailure.get());
        }

        if (payment.getAmount() == null
                || payment.getAmount().compareTo(invoice.getAmount()) != 0
                || payment.getCurrency() == null
                || !payment.getCurrency().equalsIgnoreCase(invoice.getCurrency())) {
            if (ACTIVE_UNCAPTURED_STATUSES.contains(payment.getStatus()) && payment.getCaptureRequestedAt() == null) {
                markPaymentLocallyInvalidated(payment, "PAYMENT_RECORD_AMOUNT_OR_CURRENCY_MISMATCH");
            }
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(),
                "Stored payment amount or currency does not match approved invoice.", ipAddress);
            return CapturePreCheckResult.conflict("Payment order does not match current invoice total.");
        }

        if (hasOtherPaymentInStatus(invoiceId, payment.getId(), PaymentStatus.CAPTURED)
                || (payment.getStatus() != PaymentStatus.PENDING
                    && hasOtherPaymentInStatus(invoiceId, payment.getId(), PaymentStatus.PENDING))) {
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(),
                "Another payment on this invoice is already captured or pending.", ipAddress);
            return CapturePreCheckResult.conflict("Another payment for this invoice is already pending or completed.");
        }

        String stableCaptureKey = payment.getCaptureIdempotencyKey();
        if (stableCaptureKey == null || stableCaptureKey.isBlank()) {
            stableCaptureKey = buildCaptureIdempotencyKey(payment.getId(), payment.getInvoiceSnapshotHash());
            payment.setCaptureIdempotencyKey(stableCaptureKey);
        }

        boolean wasPending = payment.getStatus() == PaymentStatus.PENDING;
        boolean wasAmbiguousRetry = !wasPending && payment.getCaptureRequestedAt() != null;

        if (wasPending || wasAmbiguousRetry) {
            if (wasPending && payment.getCaptureRequestedAt() == null) {
                payment.setCaptureRequestedAt(LocalDateTime.now(clock));
            }
            paymentRepository.saveAndFlush(payment);
            return CapturePreCheckResult.checkOrderDetailsFirst(
                stableCaptureKey, payment.getPaypalCaptureId(), wasPending
            );
        }

        // First capture dispatch: durably persist capture intent before external PayPal call
        payment.setCaptureRequestedAt(LocalDateTime.now(clock));
        paymentRepository.saveAndFlush(payment);
        return CapturePreCheckResult.firstCaptureCall(stableCaptureKey);
    }

    private CapturePreCheckResult idempotentCaptureReplay(
            CheckoutAuthority authority,
            Long invoiceId,
            String orderId,
            Payment payment,
            Invoice invoice,
            String ipAddress) {
        Map<String, Object> replayDetails = new LinkedHashMap<>();
        replayDetails.put("invoiceId", invoiceId);
        replayDetails.put("paypalOrderId", orderId);
        replayDetails.put("paypalCaptureId", payment.getPaypalCaptureId() != null ? payment.getPaypalCaptureId() : "");
        enrichAuthorityAuditDetails(replayDetails, authority);

        auditLogService.logEvent(
            authority.sellerUserId(),
            "PAYMENT",
            payment.getId(),
            "PAYMENT_CAPTURE_IDEMPOTENT_REPLAY",
            authority.auditActor(),
            replayDetails,
            ipAddress
        );
        return CapturePreCheckResult.immediate(PaymentResponse.from(payment, invoice, true));
    }

    private CaptureRemoteOutcome executeRemotePayPalVerification(
            CheckoutAuthority authority,
            Long paymentId,
            CapturePreCheckResult preCheck,
            String orderId,
            String ipAddress) {
        try {
            if (!preCheck.checkOrderDetailsFirst()) {
                return executeCaptureWithAlreadyCapturedFallback(
                    authority, paymentId, orderId, preCheck.captureIdempotencyKey(), ipAddress
                );
            }

            PayPalOrderDetailsResult orderDetails = payPalSandboxClient.getOrderDetails(orderId);
            Optional<PayPalCaptureResult> existingCapture = orderDetails.resolveCapture(preCheck.expectedCaptureId());
            if (existingCapture.isPresent()) {
                return CaptureRemoteOutcome.captured(existingCapture.get());
            }

            if (preCheck.wasPreviouslyPending()) {
                auditVerificationFailure(authority, "PAYMENT", paymentId, null,
                    "PayPal order details did not contain capture details for pending payment.", ipAddress);
                throw new PaymentVerificationException(
                    "PayPal Sandbox order does not yet contain verifiable capture details."
                );
            }

            String remoteStatus = orderDetails.orderStatus() != null
                ? orderDetails.orderStatus().toUpperCase(Locale.ROOT)
                : "";

            if ("VOIDED".equals(remoteStatus)) {
                return CaptureRemoteOutcome.voided(remoteStatus);
            }

            if ("COMPLETED".equals(remoteStatus)) {
                auditVerificationFailure(authority, "PAYMENT", paymentId, null,
                    "PayPal order reported COMPLETED without any capture details.", ipAddress);
                throw new PaymentVerificationException(
                    "PayPal Sandbox order status COMPLETED lacked capture details."
                );
            }

            if (!"APPROVED".equals(remoteStatus)) {
                Map<String, Object> details = safeFailureDetails(null, orderId, null,
                    "PayPal order status is " + remoteStatus + " (not APPROVED) during recovery.");
                enrichAuthorityAuditDetails(details, authority);
                auditLogService.logIndependentEvent(
                    authority.sellerUserId(),
                    "PAYMENT",
                    paymentId,
                    "PAYMENT_CAPTURE_FAILED",
                    authority.auditActor(),
                    details,
                    ipAddress
                );
                throw new PaymentVerificationException(
                    "PayPal Sandbox order is not approved for capture (status: " + remoteStatus + ")."
                );
            }

            return executeCaptureWithAlreadyCapturedFallback(
                authority, paymentId, orderId, preCheck.captureIdempotencyKey(), ipAddress
            );
        } catch (PayPalIntegrationException ex) {
            Map<String, Object> details = safeFailureDetails(null, orderId, null, ex.getMessage());
            enrichAuthorityAuditDetails(details, authority);
            auditLogService.logIndependentEvent(
                authority.sellerUserId(),
                "PAYMENT",
                paymentId,
                "PAYMENT_CAPTURE_FAILED",
                authority.auditActor(),
                details,
                ipAddress
            );
            throw ex;
        }
    }

    private CaptureRemoteOutcome executeCaptureWithAlreadyCapturedFallback(
            CheckoutAuthority authority,
            Long paymentId,
            String orderId,
            String captureIdempotencyKey,
            String ipAddress) {
        try {
            return CaptureRemoteOutcome.captured(payPalSandboxClient.captureOrder(orderId, captureIdempotencyKey));
        } catch (PayPalApiException ex) {
            if (!ex.isOrderAlreadyCaptured()) {
                throw ex;
            }
            Map<String, Object> details = safeFailureDetails(null, orderId, null, ex.getMessage());
            enrichAuthorityAuditDetails(details, authority);
            auditLogService.logIndependentEvent(
                authority.sellerUserId(),
                "PAYMENT",
                paymentId,
                "PAYMENT_CAPTURE_ALREADY_CAPTURED_DETECTED",
                authority.auditActor(),
                details,
                ipAddress
            );
            PayPalOrderDetailsResult orderDetails = payPalSandboxClient.getOrderDetails(orderId);
            Optional<PayPalCaptureResult> recovered = orderDetails.resolveCapture(null);
            if (recovered.isEmpty()) {
                auditVerificationFailure(authority, "PAYMENT", paymentId, null,
                    "PayPal reported ORDER_ALREADY_CAPTURED but GET order details lacked capture records.",
                    ipAddress);
                throw new PaymentVerificationException(
                    "PayPal reported the order already captured, but capture details could not be verified."
                );
            }
            return CaptureRemoteOutcome.captured(recovered.get());
        }
    }

    private CaptureFinalizeResult finalizeCaptureVerification(
            CheckoutAuthority authority,
            Long invoiceId,
            String orderId,
            CaptureRemoteOutcome remoteOutcome,
            String ipAddress) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));
        Payment payment = paymentRepository.findByPaypalOrderIdForUpdate(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment order not found."));

        if (payment.getStatus() == PaymentStatus.CAPTURED && invoice.getStatus() == InvoiceStatus.PAID) {
            return CaptureFinalizeResult.success(PaymentResponse.from(payment, invoice, true));
        }

        if (payment.getStatus() == PaymentStatus.CANCELLED || payment.getStatus() == PaymentStatus.FAILED) {
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(),
                "Order became cancelled or failed before capture completion.", ipAddress);
            return CaptureFinalizeResult.conflict("Payment order can no longer be captured.");
        }

        if (remoteOutcome.voided()) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setCapturedAt(null);
            payment.setPaypalResponseSummary(mergeSummary(payment.getPaypalResponseSummary(), Map.of(
                "orderStatus", "VOIDED",
                "verificationFailure", "PAYPAL_ORDER_VOIDED"
            )));
            paymentRepository.saveAndFlush(payment);

            Map<String, Object> voidAudit = new LinkedHashMap<>();
            voidAudit.put("invoiceId", invoiceId);
            voidAudit.put("paypalOrderId", orderId);
            voidAudit.put("orderStatus", "VOIDED");
            enrichAuthorityAuditDetails(voidAudit, authority);

            auditLogService.logEvent(
                authority.sellerUserId(),
                "PAYMENT",
                payment.getId(),
                "PAYMENT_CAPTURE_FAILED",
                authority.auditActor(),
                voidAudit,
                ipAddress
            );
            return CaptureFinalizeResult.declinedError("PayPal Sandbox order was voided before capture.");
        }

        PayPalCaptureResult captureResult = remoteOutcome.captureResult();

        boolean linkRevokedOrExpired = authority.isCustomerLink() && !isLinkCurrentlyUsableInTx(authority.paymentLinkId());
        String expectedSnapshot = authority.expectedLinkSnapshotHash() != null
            ? authority.expectedLinkSnapshotHash()
            : payment.getInvoiceSnapshotHash();
        Optional<String> validationFailure = evaluateApprovalValidity(
            invoice, authority.sellerUserId(), expectedSnapshot
        );
        if (linkRevokedOrExpired
                || validationFailure.isPresent()
                || hasOtherPaymentInStatus(invoiceId, payment.getId(), PaymentStatus.CAPTURED)) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setCapturedAt(null);
            payment.setPaypalResponseSummary(mergeSummary(payment.getPaypalResponseSummary(), Map.of(
                "verificationFailure", linkRevokedOrExpired
                    ? "PAYMENT_LINK_INVALIDATED_DURING_CAPTURE"
                    : "INVOICE_STATE_OR_SNAPSHOT_CHANGED_DURING_CAPTURE",
                "paypalCaptureStatusObserved", captureResult.captureStatus()
            )));
            paymentRepository.saveAndFlush(payment);
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(),
                linkRevokedOrExpired
                    ? "Payment link was revoked or expired while PayPal capture was executing."
                    : "Invoice approval or snapshot changed while PayPal capture was executing.",
                ipAddress);
            return CaptureFinalizeResult.conflict("Invoice approval or payment link was invalidated during payment capture.");
        }

        Optional<String> financialMismatch = verifyCaptureFinancials(orderId, payment, invoice, captureResult);
        if (financialMismatch.isPresent()) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setCapturedAt(null);
            payment.setPaypalResponseSummary(mergeSummary(payment.getPaypalResponseSummary(), Map.of(
                "verificationFailure", financialMismatch.get(),
                "paypalOrderId", captureResult.paypalOrderId(),
                "paypalCaptureStatus", captureResult.captureStatus()
            )));
            paymentRepository.saveAndFlush(payment);
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(), financialMismatch.get(), ipAddress);
            return CaptureFinalizeResult.verificationError(financialMismatch.get());
        }

        Map<String, Object> updatedSummary = mergeSummary(payment.getPaypalResponseSummary(), captureResult.summary());
        if (captureResult.pendingReason() != null && !captureResult.pendingReason().isBlank()) {
            updatedSummary.putIfAbsent("pendingReason", captureResult.pendingReason());
        }
        LocalDateTime now = LocalDateTime.now(clock);

        if (captureResult.completed()) {
            payment.setPaypalCaptureId(captureResult.paypalCaptureId());
            payment.setStatus(PaymentStatus.CAPTURED);
            payment.setCapturedAt(now);
            payment.setPaypalResponseSummary(updatedSummary);
            Payment savedPayment = paymentRepository.saveAndFlush(payment);

            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setPaidAt(now);
            Invoice savedInvoice = invoiceRepository.saveAndFlush(invoice);

            Map<String, Object> paymentAudit = new LinkedHashMap<>();
            paymentAudit.put("invoiceId", savedInvoice.getId());
            paymentAudit.put("invoiceNumber", savedInvoice.getInvoiceNumber());
            paymentAudit.put("paypalOrderId", savedPayment.getPaypalOrderId());
            paymentAudit.put("paypalCaptureId", savedPayment.getPaypalCaptureId());
            paymentAudit.put("amount", savedPayment.getAmount().toPlainString());
            paymentAudit.put("currency", savedPayment.getCurrency());
            enrichAuthorityAuditDetails(paymentAudit, authority);

            auditLogService.logEvent(
                authority.sellerUserId(),
                "PAYMENT",
                savedPayment.getId(),
                "PAYMENT_CAPTURE_SUCCEEDED",
                authority.auditActor(),
                paymentAudit,
                ipAddress
            );

            Map<String, Object> invoicePaidAudit = new LinkedHashMap<>();
            invoicePaidAudit.put("invoiceNumber", savedInvoice.getInvoiceNumber());
            invoicePaidAudit.put("paypalOrderId", savedPayment.getPaypalOrderId());
            invoicePaidAudit.put("paypalCaptureId", savedPayment.getPaypalCaptureId());
            invoicePaidAudit.put("amount", savedPayment.getAmount().toPlainString());
            invoicePaidAudit.put("currency", savedPayment.getCurrency());
            enrichAuthorityAuditDetails(invoicePaidAudit, authority);

            auditLogService.logEvent(
                authority.sellerUserId(),
                "INVOICE",
                savedInvoice.getId(),
                "INVOICE_PAID",
                AuditActor.SYSTEM,
                invoicePaidAudit,
                ipAddress
            );

            return CaptureFinalizeResult.success(PaymentResponse.from(savedPayment, savedInvoice, false));
        }

        if (captureResult.pending()) {
            payment.setPaypalCaptureId(captureResult.paypalCaptureId());
            payment.setStatus(PaymentStatus.PENDING);
            payment.setCapturedAt(null);
            payment.setPaypalResponseSummary(updatedSummary);
            Payment savedPayment = paymentRepository.saveAndFlush(payment);

            Map<String, Object> auditDetails = new LinkedHashMap<>();
            auditDetails.put("invoiceId", invoice.getId());
            auditDetails.put("paypalOrderId", savedPayment.getPaypalOrderId());
            auditDetails.put("paypalCaptureId", savedPayment.getPaypalCaptureId());
            auditDetails.put("captureStatus", captureResult.captureStatus());
            if (captureResult.pendingReason() != null) {
                auditDetails.put("pendingReason", captureResult.pendingReason());
            }
            enrichAuthorityAuditDetails(auditDetails, authority);

            auditLogService.logEvent(
                authority.sellerUserId(),
                "PAYMENT",
                savedPayment.getId(),
                "PAYMENT_CAPTURE_PENDING",
                authority.auditActor(),
                auditDetails,
                ipAddress
            );

            return CaptureFinalizeResult.success(PaymentResponse.from(savedPayment, invoice, false));
        }

        payment.setPaypalCaptureId(captureResult.paypalCaptureId());
        payment.setStatus(PaymentStatus.FAILED);
        payment.setCapturedAt(null);
        payment.setPaypalResponseSummary(updatedSummary);
        Payment savedFailed = paymentRepository.saveAndFlush(payment);

        Map<String, Object> failureAudit = new LinkedHashMap<>();
        failureAudit.put("invoiceId", invoice.getId());
        failureAudit.put("paypalOrderId", savedFailed.getPaypalOrderId());
        failureAudit.put("paypalCaptureId", captureResult.paypalCaptureId());
        failureAudit.put("captureStatus", captureResult.captureStatus());
        if (captureResult.pendingReason() != null) {
            failureAudit.put("reason", captureResult.pendingReason());
        }
        enrichAuthorityAuditDetails(failureAudit, authority);

        auditLogService.logEvent(
            authority.sellerUserId(),
            "PAYMENT",
            savedFailed.getId(),
            "PAYMENT_CAPTURE_FAILED",
            authority.auditActor(),
            failureAudit,
            ipAddress
        );

        String message = "PayPal Sandbox did not complete the payment capture (status: "
            + captureResult.captureStatus() + ").";
        return CaptureFinalizeResult.declinedError(message);
    }

    private CancelOutcome performLocalCancel(
            CheckoutAuthority authority,
            Long invoiceId,
            String orderId,
            String reason,
            String ipAddress) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));
        Payment payment = paymentRepository.findByPaypalOrderIdForUpdate(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment order not found."));

        if (!invoice.getUser().getId().equals(authority.sellerUserId())
                || !payment.getInvoice().getId().equals(invoiceId)
                || !matchesAuthorityLink(payment, authority)) {
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(), "Ownership or payment-link binding violation on cancel attempt.", ipAddress);
            throw new ForbiddenOperationException("You do not have permission to cancel this payment.");
        }

        if (authority.isCustomerLink()) {
            ensureLinkCurrentlyUsableInTx(authority.paymentLinkId());
        }

        if (payment.getStatus() == PaymentStatus.CAPTURED || invoice.getStatus() == InvoiceStatus.PAID) {
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(), "Cannot cancel an already captured payment.", ipAddress);
            return new CancelOutcome(null, "Captured or paid invoice payments cannot be cancelled.");
        }

        if (payment.getStatus() == PaymentStatus.PENDING) {
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(), "Cannot cancel a pending payment.", ipAddress);
            return new CancelOutcome(null, "Pending PayPal payments cannot be cancelled while verification is in progress.");
        }

        if (payment.getStatus() == PaymentStatus.FAILED) {
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(), "Cannot cancel a failed payment order.", ipAddress);
            return new CancelOutcome(null, "Failed payment orders cannot be cancelled.");
        }

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            Map<String, Object> replayDetails = new LinkedHashMap<>();
            replayDetails.put("invoiceId", invoiceId);
            replayDetails.put("paypalOrderId", orderId);
            enrichAuthorityAuditDetails(replayDetails, authority);

            auditLogService.logEvent(
                authority.sellerUserId(),
                "PAYMENT",
                payment.getId(),
                "PAYMENT_ORDER_CANCEL_IDEMPOTENT_REPLAY",
                authority.auditActor(),
                replayDetails,
                ipAddress
            );
            return new CancelOutcome(PaymentResponse.from(payment, invoice, true), null);
        }

        if (payment.getCaptureRequestedAt() != null) {
            auditVerificationFailure(authority, "PAYMENT", payment.getId(),
                payment.getInvoiceSnapshotHash(),
                "Blocked local cancellation after capture attempt was already initiated.", ipAddress);
            return new CancelOutcome(null,
                "Cannot cancel this order because a capture attempt was already initiated. Verify capture status instead.");
        }

        payment.setStatus(PaymentStatus.CANCELLED);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("localCancelled", true);
        extra.put("paypalCancellationConfirmed", false);
        extra.put("cancelledAt", LocalDateTime.now(clock).toString());
        if (reason != null) {
            extra.put("cancellationReason", reason);
        }
        payment.setPaypalResponseSummary(mergeSummary(payment.getPaypalResponseSummary(), extra));
        Payment saved = paymentRepository.saveAndFlush(payment);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("invoiceId", invoiceId);
        auditDetails.put("paypalOrderId", orderId);
        auditDetails.put("paypalCancellationConfirmed", false);
        if (reason != null) {
            auditDetails.put("reason", reason);
        }
        enrichAuthorityAuditDetails(auditDetails, authority);

        auditLogService.logEvent(
            authority.sellerUserId(),
            "PAYMENT",
            saved.getId(),
            "PAYMENT_ORDER_CANCELLED_LOCALLY",
            authority.auditActor(),
            auditDetails,
            ipAddress
        );

        return new CancelOutcome(PaymentResponse.from(saved, invoice, false), null);
    }

    private void verifyInvoiceOwnershipBeforeLease(Long authenticatedUserId, Long invoiceId, String ipAddress) {
        Long ownerUserId = transactionTemplate.execute(status -> {
            Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));
            return invoice.getUser().getId();
        });
        if (ownerUserId == null || !ownerUserId.equals(authenticatedUserId)) {
            auditVerificationFailure(CheckoutAuthority.seller(authenticatedUserId), "INVOICE", invoiceId, null,
                "Ownership violation when attempting PayPal order creation", ipAddress);
            throw new ForbiddenOperationException("You do not have permission to initiate payment for this invoice.");
        }
    }

    private void ensureLinkCurrentlyUsableInTx(Long paymentLinkId) {
        PaymentLink link = paymentLinkRepository.findById(paymentLinkId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment link not found."));
        if (link.isRevoked()) {
            throw new PaymentLinkGoneException("This payment link has been revoked and can no longer be used.");
        }
        if (link.isExpired(LocalDateTime.now(clock))) {
            throw new PaymentLinkGoneException("This payment link has expired. Please request a new link from the seller.");
        }
    }

    private boolean isLinkCurrentlyUsableInTx(Long paymentLinkId) {
        return paymentLinkRepository.findById(paymentLinkId)
            .map(link -> link.isUsableAt(LocalDateTime.now(clock)))
            .orElse(false);
    }

    private PaymentSummaryBound resolveLinkBoundPaymentSummary(
            ValidatedLinkContext linkCtx,
            String orderId,
            String ipAddress) {
        PaymentSummaryBound summary = transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.findByPaypalOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment order not found."));
            Long paymentInvoiceId = payment.getInvoice().getId();
            Long boundLinkId = payment.getPaymentLink() != null ? payment.getPaymentLink().getId() : null;
            return new PaymentSummaryBound(payment.getId(), paymentInvoiceId, boundLinkId);
        });
        if (summary == null) {
            throw new ResourceNotFoundException("Payment order not found.");
        }
        if (!summary.invoiceId().equals(linkCtx.invoiceId())
                || summary.paymentLinkId() == null
                || !summary.paymentLinkId().equals(linkCtx.paymentLinkId())) {
            auditVerificationFailure(
                CheckoutAuthority.customerLink(linkCtx.sellerUserId(), linkCtx.paymentLinkId(), linkCtx.linkSnapshotHash()),
                "PAYMENT",
                summary.paymentId(),
                null,
                "Order binding violation on customer payment link for PayPal order " + orderId,
                ipAddress
            );
            throw new ForbiddenOperationException("PayPal order does not belong to this payment link.");
        }
        return summary;
    }

    private String resolveActiveOrderIdForLink(ValidatedLinkContext linkCtx) {
        return transactionTemplate.execute(status -> paymentRepository
            .findByInvoiceIdAndStatusIn(linkCtx.invoiceId(), ACTIVE_UNCAPTURED_STATUSES).stream()
            .filter(p -> p.getPaymentLink() != null && linkCtx.paymentLinkId().equals(p.getPaymentLink().getId()))
            .findFirst()
            .map(Payment::getPaypalOrderId)
            .orElse(null));
    }

    private boolean matchesAuthorityLink(Payment payment, CheckoutAuthority authority) {
        if (!authority.isCustomerLink()) {
            return true;
        }
        return payment.getPaymentLink() != null
            && authority.paymentLinkId().equals(payment.getPaymentLink().getId());
    }

    private void markIntentCompleted(Long intentId, String paypalOrderId) {
        if (intentId == null) {
            return;
        }
        paymentOrderIntentRepository.findById(intentId).ifPresent(intent -> {
            intent.setStatus(PaymentOrderIntentStatus.COMPLETED);
            intent.setPaypalOrderId(paypalOrderId);
            paymentOrderIntentRepository.saveAndFlush(intent);
        });
    }

    private void markIntentAborted(Long intentId) {
        if (intentId == null) {
            return;
        }
        paymentOrderIntentRepository.findById(intentId).ifPresent(intent -> {
            if (intent.getStatus() == PaymentOrderIntentStatus.PENDING) {
                intent.setStatus(PaymentOrderIntentStatus.ABORTED);
                paymentOrderIntentRepository.saveAndFlush(intent);
            }
        });
    }

    private void abortPendingIntents(Long invoiceId) {
        List<PaymentOrderIntent> pending = paymentOrderIntentRepository
            .findByInvoiceIdAndStatus(invoiceId, PaymentOrderIntentStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        for (PaymentOrderIntent intent : pending) {
            intent.setStatus(PaymentOrderIntentStatus.ABORTED);
        }
        paymentOrderIntentRepository.saveAllAndFlush(pending);
    }

    private Optional<String> evaluateApprovalValidity(
            Invoice invoice,
            Long expectedApproverUserId,
            String expectedPaymentSnapshotHash) {
        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            return Optional.of("Invoice must be in APPROVED status before payment. Current status: "
                + invoice.getStatus());
        }
        if (invoice.getApprovedAt() == null
                || invoice.getApprovedByUserId() == null
                || invoice.getApprovedSnapshotHash() == null
                || invoice.getApprovedSnapshotHash().isBlank()) {
            return Optional.of("Invoice is missing valid explicit approval metadata.");
        }
        if (!invoice.getApprovedByUserId().equals(expectedApproverUserId)) {
            return Optional.of("Invoice approval belongs to a different user.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (invoice.getApprovedAt().plusHours(approvalMaxAgeHours).isBefore(now)) {
            return Optional.of("Invoice approval has expired. Please review and re-approve the invoice.");
        }
        if (invoice.getDueDate() == null || invoice.getDueDate().isBefore(LocalDate.now(clock))) {
            return Optional.of("Invoice due date has expired. Update and re-approve the invoice before payment.");
        }
        if (invoice.getAmount() == null || invoice.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.of("Invoice amount must be positive before payment.");
        }
        if (invoice.getCurrency() == null
                || !ISO_CURRENCY_PATTERN.matcher(invoice.getCurrency().trim().toUpperCase(Locale.ROOT)).matches()) {
            return Optional.of("Invoice currency is invalid.");
        }
        if (invoice.getDescription() == null || invoice.getDescription().isBlank()) {
            return Optional.of("Invoice description is required before payment.");
        }

        String currentHash = invoiceSnapshotService.computeHash(invoice);
        if (!invoiceSnapshotService.hashesMatch(currentHash, invoice.getApprovedSnapshotHash())) {
            return Optional.of("Invoice details no longer match the approved snapshot. Please re-approve.");
        }
        if (expectedPaymentSnapshotHash != null
                && !invoiceSnapshotService.hashesMatch(currentHash, expectedPaymentSnapshotHash)) {
            return Optional.of("Payment order snapshot no longer matches the approved invoice details.");
        }
        return Optional.empty();
    }

    private Optional<String> verifyCaptureFinancials(
            String expectedOrderId,
            Payment payment,
            Invoice invoice,
            PayPalCaptureResult captureResult) {
        if (captureResult == null || !expectedOrderId.equals(captureResult.paypalOrderId())) {
            return Optional.of("PayPal capture verification failed: order ID mismatch.");
        }
        if (captureResult.paypalCaptureId() == null || captureResult.paypalCaptureId().isBlank()) {
            return Optional.of("PayPal capture verification failed: missing capture ID.");
        }
        if (captureResult.amount() == null
                || invoice.getAmount() == null
                || payment.getAmount() == null
                || captureResult.amount().compareTo(invoice.getAmount()) != 0
                || captureResult.amount().compareTo(payment.getAmount()) != 0) {
            return Optional.of("PayPal capture verification failed: captured amount mismatch.");
        }
        if (captureResult.currency() == null
                || !captureResult.currency().equalsIgnoreCase(invoice.getCurrency())
                || !captureResult.currency().equalsIgnoreCase(payment.getCurrency())) {
            return Optional.of("PayPal capture verification failed: captured currency mismatch.");
        }
        return Optional.empty();
    }

    private OwnedPaymentSummary resolveOwnedPaymentSummary(
            Long authenticatedUserId,
            String orderId,
            String ipAddress) {
        OwnedPaymentSummary summary = transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.findByPaypalOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment order not found."));
            Invoice invoice = payment.getInvoice();
            return new OwnedPaymentSummary(payment.getId(), invoice.getId(), invoice.getUser().getId());
        });
        if (summary == null) {
            throw new ResourceNotFoundException("Payment order not found.");
        }
        if (!summary.ownerUserId().equals(authenticatedUserId)) {
            auditVerificationFailure(CheckoutAuthority.seller(authenticatedUserId), "PAYMENT", summary.paymentId(), null,
                "Ownership violation accessing PayPal order " + orderId, ipAddress);
            throw new ForbiddenOperationException("You do not have permission to access this payment order.");
        }
        return summary;
    }

    private void invalidateStaleActiveOrders(Invoice invoice, String currentHash, Long requiredPaymentLinkId) {
        List<Payment> activeOrders = paymentRepository.findByInvoiceIdAndStatusIn(
            invoice.getId(), ACTIVE_UNCAPTURED_STATUSES
        );
        for (Payment order : activeOrders) {
            boolean wrongCustomerLink = requiredPaymentLinkId != null
                && (order.getPaymentLink() == null || !requiredPaymentLinkId.equals(order.getPaymentLink().getId()));
            boolean stale = !isValidReusableOrder(order, invoice)
                || !invoiceSnapshotService.hashesMatch(currentHash, order.getInvoiceSnapshotHash())
                || wrongCustomerLink;
            if (stale) {
                if (order.getCaptureRequestedAt() != null) {
                    throw new InvalidInvoiceStateException(
                        "Cannot supersede an uncaptured order with an ambiguous capture attempt."
                    );
                }
                markPaymentLocallyInvalidated(
                    order,
                    wrongCustomerLink ? "SUPERSEDED_FOR_CUSTOMER_PAYMENT_LINK" : "STALE_ORDER_SUPERSEDED"
                );
            }
        }
    }

    private void markPaymentLocallyInvalidated(Payment payment, String reason) {
        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setPaypalResponseSummary(mergeSummary(payment.getPaypalResponseSummary(), Map.of(
            "localInvalidated", true,
            "localInvalidationReason", reason,
            "localInvalidatedAt", LocalDateTime.now(clock).toString()
        )));
        paymentRepository.saveAndFlush(payment);
    }

    private boolean isValidReusableOrder(Payment order, Invoice invoice) {
        return ACTIVE_UNCAPTURED_STATUSES.contains(order.getStatus())
            && order.getPaypalOrderId() != null
            && !order.getPaypalOrderId().isBlank()
            && order.getPaypalApprovalUrl() != null
            && !order.getPaypalApprovalUrl().isBlank()
            && order.getAmount() != null
            && order.getAmount().compareTo(invoice.getAmount()) == 0
            && order.getCurrency() != null
            && order.getCurrency().equalsIgnoreCase(invoice.getCurrency());
    }

    private boolean hasOtherPaymentInStatus(Long invoiceId, Long currentPaymentId, PaymentStatus status) {
        return paymentRepository.findByInvoiceIdAndStatusIn(invoiceId, List.of(status)).stream()
            .anyMatch(existing -> !existing.getId().equals(currentPaymentId));
    }

    private void auditVerificationFailure(
            CheckoutAuthority authority,
            String entityType,
            Long entityId,
            String snapshotHash,
            String reason,
            String ipAddress) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        if (snapshotHash != null) {
            details.put("snapshotHash", snapshotHash);
        }
        enrichAuthorityAuditDetails(details, authority);
        auditLogService.logIndependentEvent(
            authority.sellerUserId(),
            entityType,
            entityId,
            "PAYMENT_VERIFICATION_FAILED",
            authority.auditActor(),
            details,
            ipAddress
        );
    }

    private void enrichAuthorityAuditDetails(Map<String, Object> details, CheckoutAuthority authority) {
        details.put("channel", authority.channel());
        if (authority.paymentLinkId() != null) {
            details.put("paymentLinkId", authority.paymentLinkId());
        }
    }

    private Map<String, Object> safeFailureDetails(
            Long invoiceId,
            String orderId,
            String snapshotHash,
            String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (invoiceId != null) {
            details.put("invoiceId", invoiceId);
        }
        if (orderId != null) {
            details.put("paypalOrderId", orderId);
        }
        if (snapshotHash != null) {
            details.put("snapshotHash", snapshotHash);
        }
        if (message != null && !message.isBlank()) {
            details.put("reason", message.length() > 300 ? message.substring(0, 300) : message);
        }
        return details;
    }

    private Map<String, Object> mergeSummary(Map<String, Object> existing, Map<String, Object> updates) {
        Map<String, Object> merged = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
        if (updates != null) {
            merged.putAll(updates);
        }
        return merged;
    }

    private String buildOrderIdempotencyKey(Long invoiceId, String snapshotHash, int attemptNumber) {
        return "ord-" + invoiceId + "-" + snapshotHash.substring(0, 16) + "-a" + attemptNumber;
    }

    private String buildCaptureIdempotencyKey(Long paymentId, String snapshotHash) {
        String hashPrefix = snapshotHash != null && snapshotHash.length() >= 16
            ? snapshotHash.substring(0, 16)
            : "nosnap";
        return "cap-" + paymentId + "-" + hashPrefix;
    }

    private String validateOrderId(String rawOrderId) {
        if (rawOrderId == null || !ORDER_ID_PATTERN.matcher(rawOrderId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid PayPal order ID format.");
        }
        return rawOrderId.trim();
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

    private Set<String> parseAllowedOriginKeys(String allowedOriginsRaw, String fallbackOrigin) {
        Set<String> keys = Arrays.stream((allowedOriginsRaw != null ? allowedOriginsRaw : "").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(this::extractOriginKeySafe)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toSet());
        if (keys.isEmpty()) {
            extractOriginKeySafe(fallbackOrigin).ifPresent(keys::add);
        }
        return Set.copyOf(keys);
    }

    private Optional<String> extractOriginKeySafe(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            return Optional.of(normalizeOriginKey(uri));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String normalizeOriginKey(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return port >= 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
    }

    private String resolveRedirectUrl(String requestedUrl, Long invoiceId, String mode) {
        String candidate = requestedUrl != null && !requestedUrl.isBlank()
            ? requestedUrl.trim()
            : defaultFrontendOrigin + "/invoices/" + invoiceId + "?paypal=" + mode;
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid redirect URL format.");
        }
        String scheme = uri.getScheme();
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Redirect URL must use HTTP or HTTPS with a valid host.");
        }
        return uri.toString();
    }

    private String resolveRedirectUrlForPaymentLink(String requestedUrl, String rawToken, String mode) {
        if (requestedUrl == null || requestedUrl.isBlank()) {
            return defaultFrontendOrigin + "/pay/" + rawToken + "?paypal=" + mode;
        }
        URI uri;
        try {
            uri = URI.create(requestedUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid redirect URL format.");
        }
        String scheme = uri.getScheme();
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Redirect URL must use HTTP or HTTPS with a valid host.");
        }
        String originKey = normalizeOriginKey(uri);
        if (!allowedFrontendOriginKeys.contains(originKey)) {
            throw new IllegalArgumentException("Redirect URL origin is not allowed for customer payment checkout.");
        }
        String expectedPath = "/pay/" + rawToken;
        if (!expectedPath.equals(uri.getPath())) {
            throw new IllegalArgumentException("Redirect URL path must target the active customer payment link.");
        }
        return uri.toString();
    }

    private ReentrantLock lockForInvoice(Long invoiceId) {
        return invoiceLockStripes[Math.floorMod(invoiceId.hashCode(), invoiceLockStripes.length)];
    }

    public record PaymentOrderOutcome(PaymentResponse response, boolean reused) {
    }

    public record PublicPaymentOrderOutcome(PublicPaymentOrderResponse response, boolean reused) {
    }

    public record PublicCaptureOutcome(PublicPaymentInvoiceResponse response, boolean pending) {
    }

    private record CheckoutAuthority(
        Long sellerUserId,
        Long paymentLinkId,
        String expectedLinkSnapshotHash,
        AuditActor auditActor,
        String channel
    ) {
        static CheckoutAuthority seller(Long sellerUserId) {
            return new CheckoutAuthority(sellerUserId, null, null, AuditActor.USER, "SELLER_PORTAL");
        }

        static CheckoutAuthority customerLink(Long sellerUserId, Long paymentLinkId, String linkSnapshotHash) {
            return new CheckoutAuthority(sellerUserId, paymentLinkId, linkSnapshotHash, AuditActor.SYSTEM, "CUSTOMER_PAYMENT_LINK");
        }

        boolean isCustomerLink() {
            return paymentLinkId != null;
        }
    }

    private record OwnedPaymentSummary(Long paymentId, Long invoiceId, Long ownerUserId) {
    }

    private record PaymentSummaryBound(Long paymentId, Long invoiceId, Long paymentLinkId) {
    }

    private record OrderPreparationStep(
        Long invoiceId,
        Long intentId,
        PaymentOrderOutcome outcome,
        PayPalCreateOrderRequest paypalRequest,
        String snapshotHash,
        String idempotencyKey
    ) {
    }

    private record OrderPersistOutcome(PaymentOrderOutcome outcome, String conflictMessage) {
    }

    private record CapturePreCheckResult(
        PaymentResponse immediateResponse,
        String conflictMessage,
        boolean checkOrderDetailsFirst,
        boolean wasPreviouslyPending,
        String expectedCaptureId,
        String captureIdempotencyKey
    ) {
        static CapturePreCheckResult immediate(PaymentResponse response) {
            return new CapturePreCheckResult(response, null, false, false, null, null);
        }

        static CapturePreCheckResult conflict(String message) {
            return new CapturePreCheckResult(null, message, false, false, null, null);
        }

        static CapturePreCheckResult firstCaptureCall(String idempotencyKey) {
            return new CapturePreCheckResult(null, null, false, false, null, idempotencyKey);
        }

        static CapturePreCheckResult checkOrderDetailsFirst(
                String idempotencyKey,
                String expectedCaptureId,
                boolean wasPreviouslyPending) {
            return new CapturePreCheckResult(null, null, true, wasPreviouslyPending, expectedCaptureId, idempotencyKey);
        }
    }

    private record CaptureRemoteOutcome(
        PayPalCaptureResult captureResult,
        boolean voided
    ) {
        static CaptureRemoteOutcome captured(PayPalCaptureResult result) {
            return new CaptureRemoteOutcome(result, false);
        }

        static CaptureRemoteOutcome voided(String status) {
            return new CaptureRemoteOutcome(null, true);
        }
    }

    private record CaptureFinalizeResult(
        PaymentResponse response,
        String conflictMessage,
        String verificationError,
        String declinedError
    ) {
        static CaptureFinalizeResult success(PaymentResponse response) {
            return new CaptureFinalizeResult(response, null, null, null);
        }

        static CaptureFinalizeResult conflict(String message) {
            return new CaptureFinalizeResult(null, message, null, null);
        }

        static CaptureFinalizeResult verificationError(String message) {
            return new CaptureFinalizeResult(null, null, message, null);
        }

        static CaptureFinalizeResult declinedError(String message) {
            return new CaptureFinalizeResult(null, null, null, message);
        }
    }

    private record CancelOutcome(PaymentResponse response, String conflictMessage) {
    }
}
