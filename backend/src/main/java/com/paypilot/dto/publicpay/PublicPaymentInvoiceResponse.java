package com.paypilot.dto.publicpay;

import com.paypilot.model.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Strict customer-facing view of an invoice accessed via a secure payment link.
 * Never exposes seller email, private seller IDs, customer email/phone, AI logs, or approval hashes.
 * When linkState is REVOKED, EXPIRED, or UNAVAILABLE, invoice terms are redacted (null).
 */
public record PublicPaymentInvoiceResponse(
    PublicLinkState linkState,
    boolean payable,
    boolean sandboxMode,
    String statusMessage,
    LocalDateTime expiresAt,
    String invoiceNumber,
    String sellerDisplayName,
    String customerName,
    String description,
    BigDecimal amount,
    String currency,
    LocalDate dueDate,
    InvoiceStatus invoiceStatus,
    PublicPaymentSummary activePayment,
    PublicPaymentReceiptResponse receipt
) {
}

