package com.paypilot.dto.email;

import java.time.LocalDateTime;

/**
 * Server-rendered responsive HTML and plain-text email preview payload.
 */
public record InvoiceEmailPreviewResponse(
    String recipientEmail,
    String recipientName,
    String subject,
    String htmlBody,
    String textBody,
    String paymentUrl,
    LocalDateTime linkExpiresAt,
    boolean mailConfigured
) {
}

