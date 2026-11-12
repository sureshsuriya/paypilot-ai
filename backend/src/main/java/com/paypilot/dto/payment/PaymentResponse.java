package com.paypilot.dto.payment;

import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Safe API projection for Payment records.
 * Never exposes OAuth tokens, PayPal credentials, idempotency secrets, or raw PayPal responses.
 */
public record PaymentResponse(
    Long id,
    Long invoiceId,
    String invoiceNumber,
    String clientName,
    InvoiceStatus invoiceStatus,
    Long paymentLinkId,
    boolean customerLinkCheckout,
    String paypalOrderId,
    String paypalCaptureId,
    PaymentStatus status,
    BigDecimal amount,
    String currency,
    String approvalUrl,
    String invoiceSnapshotHash,
    String pendingReason,
    boolean completed,
    boolean pending,
    boolean locallyCancelled,
    boolean paypalCancellationConfirmed,
    boolean reused,
    LocalDateTime capturedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    Map<String, Object> summary
) {

    private static final Set<String> EXCLUDED_SUMMARY_KEYS = Set.of(
        "password", "secret", "token", "api_key", "apikey",
        "authorization", "credential", "client_id", "clientid",
        "idempotencykey", "idempotency_key"
    );

    public static PaymentResponse from(Payment payment, Invoice invoice, boolean reused) {
        Map<String, Object> sanitizedSummary = sanitizeSummary(payment.getPaypalResponseSummary());
        String pendingReason = extractString(sanitizedSummary, "pendingReason");
        boolean locallyCancelled = payment.getStatus() == PaymentStatus.CANCELLED
            && (Boolean.TRUE.equals(sanitizedSummary.get("localCancelled"))
                || Boolean.TRUE.equals(sanitizedSummary.get("localInvalidated")));
        boolean paypalCancellationConfirmed = Boolean.TRUE.equals(
            sanitizedSummary.get("paypalCancellationConfirmed")
        );
        Long paymentLinkId = payment.getPaymentLink() != null ? payment.getPaymentLink().getId() : null;

        return new PaymentResponse(
            payment.getId(),
            invoice.getId(),
            invoice.getInvoiceNumber(),
            invoice.getClientName(),
            invoice.getStatus(),
            paymentLinkId,
            paymentLinkId != null,
            payment.getPaypalOrderId(),
            payment.getPaypalCaptureId(),
            payment.getStatus(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getPaypalApprovalUrl(),
            payment.getInvoiceSnapshotHash(),
            pendingReason,
            payment.getStatus() == PaymentStatus.CAPTURED,
            payment.getStatus() == PaymentStatus.PENDING,
            locallyCancelled,
            paypalCancellationConfirmed,
            reused,
            payment.getCapturedAt(),
            payment.getCreatedAt(),
            payment.getUpdatedAt(),
            sanitizedSummary
        );
    }

    private static Map<String, Object> sanitizeSummary(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            boolean excluded = EXCLUDED_SUMMARY_KEYS.stream().anyMatch(normalized::contains);
            if (!excluded && entry.getValue() != null) {
                safe.put(key, entry.getValue());
            }
        }
        return Collections.unmodifiableMap(safe);
    }

    private static String extractString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
