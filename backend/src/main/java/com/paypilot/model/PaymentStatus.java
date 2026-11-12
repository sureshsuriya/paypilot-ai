package com.paypilot.model;

/**
 * PayPal payment lifecycle status.
 *
 * COMPLIANCE RULE:
 *   CAPTURED requires a verified paypalCaptureId from PayPal's capture response.
 */
public enum PaymentStatus {
    CREATED,
    APPROVED,
    PENDING,
    CAPTURED,
    FAILED,
    CANCELLED
}

