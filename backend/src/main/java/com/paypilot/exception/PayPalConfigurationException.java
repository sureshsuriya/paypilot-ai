package com.paypilot.exception;

/**
 * Raised when PayPal Sandbox configuration is invalid or unsafe
 * (e.g., non-sandbox mode, non-sandbox base URL, or missing credentials).
 */
public class PayPalConfigurationException extends PayPalIntegrationException {

    public PayPalConfigurationException(String message) {
        super(message);
    }
}

