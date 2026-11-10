package com.paypilot.exception;

public class InvalidInvoiceStateException extends RuntimeException {
    public InvalidInvoiceStateException(String message) {
        super(message);
    }
}

