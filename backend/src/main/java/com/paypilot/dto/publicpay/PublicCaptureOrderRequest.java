package com.paypilot.dto.publicpay;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for capturing a customer-approved PayPal Sandbox order bound to a payment link.
 */
public record PublicCaptureOrderRequest(
    @NotBlank(message = "PayPal order ID is required")
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "Invalid PayPal order ID format")
    String orderId
) {
}

