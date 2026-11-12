package com.paypilot.exception;

/**
 * Raised when PayPal returns malformed JSON, incomplete order/capture fields,
 * missing/unsafe approval links, or unrecognized statuses.
 */
public class PayPalMalformedResponseException extends PayPalIntegrationException {

    public PayPalMalformedResponseException(String message) {
        super(message);
    }
}

