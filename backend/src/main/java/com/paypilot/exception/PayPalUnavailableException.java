package com.paypilot.exception;

/**
 * Raised when PayPal Sandbox endpoints cannot be reached or time out.
 */
public class PayPalUnavailableException extends PayPalIntegrationException {

    public PayPalUnavailableException(String message) {
        super(message);
    }
}

