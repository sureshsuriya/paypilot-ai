package com.paypilot.dto.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload to initiate or reuse a PayPal Sandbox checkout order for an APPROVED invoice.
 */
public record CreatePaymentOrderRequest(
    @NotNull(message = "Invoice ID is required")
    Long invoiceId,

    @Size(max = 500, message = "Return URL must be 500 characters or fewer")
    String returnUrl,

    @Size(max = 500, message = "Cancel URL must be 500 characters or fewer")
    String cancelUrl
) {
}

