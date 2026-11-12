package com.paypilot.model;

/**
 * Lifecycle status of a durable PayPal order-creation intent persisted before external HTTP calls.
 */
public enum PaymentOrderIntentStatus {
    PENDING,
    COMPLETED,
    ABORTED
}

