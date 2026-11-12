package com.paypilot.dto.publicpay;

import com.paypilot.model.PaymentStatus;

import java.math.BigDecimal;

/**
 * Response returned when a customer initiates or resumes a PayPal Sandbox order via a payment link.
 */
public record PublicPaymentOrderResponse(
    String paypalOrderId,
    PaymentStatus status,
    BigDecimal amount,
    String currency,
    String approvalUrl,
    boolean reused,
    boolean sandboxMode
) {
}

