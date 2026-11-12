package com.paypilot.exception;

import com.paypilot.dto.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized REST exception handling returning consistent JSON error structures
 * without leaking internal stack traces or secrets.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.validation("Validation failed", safePath(request), fieldErrors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            Exception ex,
            HttpServletRequest request) {
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of(400, "Bad Request", "Malformed request payload or parameter.", safePath(request)));
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ApiErrorResponse> handleAuthFailure(
            AuthenticationException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiErrorResponse.of(401, "Unauthorized", "Invalid email or password.", safePath(request)));
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(
            ForbiddenOperationException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiErrorResponse.of(403, "Forbidden", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiErrorResponse.of(404, "Not Found", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler(PaymentLinkGoneException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentLinkGone(
            PaymentLinkGoneException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.GONE)
            .body(ApiErrorResponse.of(410, "Gone", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler({DuplicateResourceException.class, InvalidInvoiceStateException.class})
    public ResponseEntity<ApiErrorResponse> handleConflict(
            RuntimeException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiErrorResponse.of(409, "Conflict", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAiUnavailable(
            AiServiceUnavailableException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiErrorResponse.of(503, "Service Unavailable", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler(EmailDeliveryException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailDelivery(
            EmailDeliveryException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiErrorResponse.of(503, "Service Unavailable", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler(AiUpstreamException.class)
    public ResponseEntity<ApiErrorResponse> handleAiUpstream(
            AiUpstreamException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiErrorResponse.of(502, "Bad Gateway", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of(400, "Bad Request", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler({PaymentDeclinedException.class, PaymentVerificationException.class})
    public ResponseEntity<ApiErrorResponse> handlePaymentRejected(
            RuntimeException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ApiErrorResponse.of(422, "Unprocessable Entity", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler(PayPalUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handlePayPalUnavailable(
            PayPalUnavailableException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiErrorResponse.of(503, "Service Unavailable", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler(PayPalApiException.class)
    public ResponseEntity<ApiErrorResponse> handlePayPalApiError(
            PayPalApiException ex,
            HttpServletRequest request) {
        if (ex.isClientError()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiErrorResponse.of(422, "Unprocessable Entity", ex.getMessage(), safePath(request)));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiErrorResponse.of(502, "Bad Gateway", ex.getMessage(), safePath(request)));
    }

    @ExceptionHandler(PayPalIntegrationException.class)
    public ResponseEntity<ApiErrorResponse> handlePayPalIntegrationFailure(
            PayPalIntegrationException ex,
            HttpServletRequest request) {
        String safeMessage = (ex instanceof PayPalConfigurationException || ex instanceof PayPalAuthenticationException)
            ? "PayPal Sandbox authentication or configuration failed."
            : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiErrorResponse.of(502, "Bad Gateway", safeMessage, safePath(request)));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {
        String path = safePath(request);
        log.warn("Data integrity violation at {}: {}", path, ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiErrorResponse.of(409, "Conflict", "Request conflicts with existing data constraints.", path));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {
        String path = safePath(request);
        log.error("Unhandled error at {}: {}", path, ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse.of(500, "Internal Server Error", "An unexpected error occurred.", path));
    }

    /**
     * Redacts raw secret payment-link tokens before writing paths to logs or API error JSON.
     */
    private String safePath(HttpServletRequest request) {
        String uri = request != null ? request.getRequestURI() : "";
        if (uri == null) {
            return "";
        }
        return uri.replaceFirst("^(/api/public/pay/)[^/]+", "$1[REDACTED]");
    }
}

