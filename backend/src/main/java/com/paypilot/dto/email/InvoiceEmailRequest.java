package com.paypilot.dto.email;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Seller request to preview or send a formatted invoice payment-link email.
 * The rawToken is verified against the active SHA-256 payment_link hash on the server.
 */
public record InvoiceEmailRequest(
    @NotBlank(message = "Active payment link token is required")
    @Size(max = 128, message = "Payment link token is invalid")
    String rawToken,

    @Email(message = "Recipient email must be valid")
    @Size(max = 255, message = "Recipient email must not exceed 255 characters")
    String recipientEmail,

    @Size(max = 500, message = "Custom message must not exceed 500 characters")
    String customMessage
) {
}

