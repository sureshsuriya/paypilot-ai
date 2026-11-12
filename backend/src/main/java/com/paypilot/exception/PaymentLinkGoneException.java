package com.paypilot.exception;

/**
 * Thrown when a public payment link has expired or been revoked and can no longer be used.
 */
public class PaymentLinkGoneException extends RuntimeException {

    public PaymentLinkGoneException(String message) {
        super(message);
    }
}

