package com.paypilot.dto.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Consistent JSON error response structure returned by GlobalExceptionHandler
 * and security entry points.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path,
    Map<String, String> fieldErrors
) {
    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ApiErrorResponse validation(String message, String path, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), 400, "Bad Request", message, path, fieldErrors);
    }
}

