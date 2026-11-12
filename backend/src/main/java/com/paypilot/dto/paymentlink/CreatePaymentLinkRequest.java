package com.paypilot.dto.paymentlink;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Optional configuration payload when generating or rotating a customer payment link.
 */
public record CreatePaymentLinkRequest(
    @Min(value = 1, message = "Expiration must be at least 1 day")
    @Max(value = 90, message = "Expiration must not exceed 90 days")
    Integer expiresInDays
) {
}

