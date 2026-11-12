package com.paypilot.exception;

/**
 * Thrown when SMTP email delivery is disabled, unconfigured, or rejected by the mail server.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}

