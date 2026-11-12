package com.paypilot.exception;

/**
 * Raised when PayPal OAuth2 client authentication fails or an access token is rejected.
 */
public class PayPalAuthenticationException extends PayPalIntegrationException {

    private final int httpStatusCode;
    private final String errorCode;

    public PayPalAuthenticationException(int httpStatusCode, String errorCode, String message) {
        super(message);
        this.httpStatusCode = httpStatusCode;
        this.errorCode = errorCode;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

