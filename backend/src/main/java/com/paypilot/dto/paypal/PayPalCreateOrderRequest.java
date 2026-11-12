package com.paypilot.dto.paypal;

import java.math.BigDecimal;

/**
 * Command payload for creating a PayPal Sandbox v2 Checkout order.
 * Caller supplies idempotencyKey which is passed verbatim as PayPal-Request-Id.
 */
public record PayPalCreateOrderRequest(
    String idempotencyKey,
    String invoiceNumber,
    String customId,
    String description,
    BigDecimal amount,
    String currency,
    String returnUrl,
    String cancelUrl
) {
}

