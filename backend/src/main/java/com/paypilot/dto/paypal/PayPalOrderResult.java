package com.paypilot.dto.paypal;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Normalized result of creating a PayPal Sandbox order.
 * Preserves order ID, approval link, monetary details, idempotency key,
 * and a sanitized non-sensitive summary map for persistence in Payment.
 */
public record PayPalOrderResult(
    String paypalOrderId,
    String status,
    String approvalUrl,
    String approvalLinkRel,
    String idempotencyKey,
    BigDecimal amount,
    String currency,
    Map<String, Object> summary
) {
}

