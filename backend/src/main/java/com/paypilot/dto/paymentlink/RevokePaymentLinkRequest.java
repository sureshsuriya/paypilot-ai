package com.paypilot.dto.paymentlink;

import jakarta.validation.constraints.Size;

/**
 * Optional reason payload when a seller revokes an active customer payment link.
 */
public record RevokePaymentLinkRequest(
    @Size(max = 100, message = "Revocation reason must not exceed 100 characters")
    String reason
) {
}

