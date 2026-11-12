package com.paypilot.model;

/**
 * Lifecycle status of an invoice.
 *
 * COMPLIANCE RULE:
 *   PAID may only be set by the backend after a verified PayPal capture
 *   returns status = COMPLETED and a Payment record is saved as CAPTURED.
 */
public enum InvoiceStatus {
    DRAFT,
    APPROVED,
    SENT,
    PAID,
    OVERDUE,
    CANCELLED
}

