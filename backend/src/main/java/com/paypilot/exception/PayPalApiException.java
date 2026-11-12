package com.paypilot.exception;

/**
 * Raised when PayPal Sandbox Orders API returns a 4xx or 5xx error response.
 * Carries only sanitized PayPal error metadata (name, issue, debug_id).
 */
public class PayPalApiException extends PayPalIntegrationException {

    private final int httpStatusCode;
    private final String paypalErrorName;
    private final String paypalErrorIssue;
    private final String paypalDebugId;

    public PayPalApiException(
            int httpStatusCode,
            String paypalErrorName,
            String paypalErrorIssue,
            String paypalDebugId,
            String message) {
        super(message);
        this.httpStatusCode = httpStatusCode;
        this.paypalErrorName = paypalErrorName;
        this.paypalErrorIssue = paypalErrorIssue;
        this.paypalDebugId = paypalDebugId;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public String getPaypalErrorName() {
        return paypalErrorName;
    }

    public String getPaypalErrorIssue() {
        return paypalErrorIssue;
    }

    public String getPaypalDebugId() {
        return paypalDebugId;
    }

    public boolean isServerError() {
        return httpStatusCode >= 500 && httpStatusCode < 600;
    }

    public boolean isClientError() {
        return httpStatusCode >= 400 && httpStatusCode < 500;
    }

    public boolean isOrderAlreadyCaptured() {
        return "ORDER_ALREADY_CAPTURED".equalsIgnoreCase(paypalErrorIssue)
            || "ORDER_ALREADY_CAPTURED".equalsIgnoreCase(paypalErrorName);
    }
}

