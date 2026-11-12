package com.paypilot.dto.payment;

import jakarta.validation.constraints.Size;

/**
 * Optional request payload when recording local cancellation of an uncaptured PayPal order.
 */
public record CancelPaymentRequest(
    @Size(max = 255, message = "Cancellation reason must be 255 characters or fewer")
    String reason
) {
}

