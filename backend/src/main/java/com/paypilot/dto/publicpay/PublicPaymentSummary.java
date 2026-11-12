package com.paypilot.dto.publicpay;

import com.paypilot.model.PaymentStatus;

import java.time.LocalDateTime;

/**
 * Minimal non-sensitive payment summary exposed to a customer holding a valid payment link.
 * Excludes idempotency keys, internal database IDs, and snapshot hashes.
 */
public record PublicPaymentSummary(
    String paypalOrderId,
    PaymentStatus status,
    String pendingReason,
    String approvalUrl,
    LocalDateTime updatedAt
) {
}

