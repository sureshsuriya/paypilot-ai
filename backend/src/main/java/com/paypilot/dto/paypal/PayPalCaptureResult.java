package com.paypilot.dto.paypal;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Normalized result of capturing a PayPal Sandbox order.
 * Distinguishes COMPLETED vs PENDING vs declined/failed captures so Step D
 * never treats a PENDING capture as PAID.
 */
public record PayPalCaptureResult(
    String paypalOrderId,
    String orderStatus,
    String paypalCaptureId,
    String captureStatus,
    boolean completed,
    boolean pending,
    String pendingReason,
    BigDecimal amount,
    String currency,
    String captureTime,
    Map<String, Object> summary
) {
}

