package com.paypilot.dto.publicpay;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body when a customer cancels PayPal checkout from a public payment link.
 */
public record PublicCancelOrderRequest(
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "Invalid PayPal order ID format")
    String orderId,

    @Size(max = 250, message = "Cancellation reason must be 250 characters or fewer")
    String reason
) {
}

