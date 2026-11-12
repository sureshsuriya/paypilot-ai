package com.paypilot.dto.publicpay;

/**
 * Customer-facing lifecycle state of a public payment link.
 */
public enum PublicLinkState {
    ACTIVE,
    PAID,
    REVOKED,
    EXPIRED,
    UNAVAILABLE
}

