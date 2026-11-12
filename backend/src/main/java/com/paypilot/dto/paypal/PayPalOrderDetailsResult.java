package com.paypilot.dto.paypal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Normalized snapshot of GET /v2/checkout/orders/{id} used to safely inspect
 * pending captures without blindly re-issuing POST /capture calls.
 */
public record PayPalOrderDetailsResult(
    String paypalOrderId,
    String orderStatus,
    BigDecimal amount,
    String currency,
    List<PayPalCaptureResult> captures
) {
    public Optional<PayPalCaptureResult> resolveCapture(String expectedCaptureId) {
        if (captures == null || captures.isEmpty()) {
            return Optional.empty();
        }
        if (expectedCaptureId != null && !expectedCaptureId.isBlank()) {
            return captures.stream()
                .filter(capture -> expectedCaptureId.equals(capture.paypalCaptureId()))
                .findFirst();
        }
        return Optional.of(captures.get(0));
    }
}

