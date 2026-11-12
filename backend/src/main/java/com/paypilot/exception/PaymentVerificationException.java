package com.paypilot.exception;

/**
 * Raised when a PayPal payment response fails authoritative backend verification
 * (e.g., mismatched order ID, amount, currency, or missing capture details).
 */
public class PaymentVerificationException extends RuntimeException {

    public PaymentVerificationException(String message) {
        super(message);
    }
}

