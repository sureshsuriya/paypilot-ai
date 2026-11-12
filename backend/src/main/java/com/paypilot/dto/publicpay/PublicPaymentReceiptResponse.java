package com.paypilot.dto.publicpay;

import com.paypilot.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Verified customer payment receipt returned only after PayPal capture verification confirms PAID.
 */
public record PublicPaymentReceiptResponse(
    String invoiceNumber,
    String sellerDisplayName,
    String customerName,
    String description,
    BigDecimal amount,
    String currency,
    String paypalOrderId,
    String paypalCaptureId,
    PaymentStatus paymentStatus,
    LocalDateTime paidAt,
    boolean sandboxMode
) {
}

