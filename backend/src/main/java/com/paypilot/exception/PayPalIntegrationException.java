package com.paypilot.exception;

/**
 * Base exception for PayPal Sandbox client failures.
 * Messages must never contain client secrets, access tokens, or raw authorization headers.
 */
public class PayPalIntegrationException extends RuntimeException {

    public PayPalIntegrationException(String message) {
        super(message);
    }

    public PayPalIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}

