package com.paypilot.exception;

/**
 * Raised when a PayPal Sandbox capture is explicitly declined or fails without completing.
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String message) {
        super(message);
    }
}

