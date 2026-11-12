package com.paypilot.dto.publicpay;

import jakarta.validation.constraints.Size;

/**
 * Optional return/cancel URLs provided by the Customer Portal browser origin.
 * Strictly validated by PaymentService against configured allowed frontend origins and /pay/ path.
 */
public record PublicCreateOrderRequest(
    @Size(max = 500)
    String returnUrl,

    @Size(max = 500)
    String cancelUrl
) {
}

