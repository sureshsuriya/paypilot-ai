package com.paypilot.dto.email;

import java.time.LocalDateTime;

/**
 * Authoritative response confirming backend email dispatch without exposing credentials or raw tokens.
 */
public record InvoiceEmailSendResponse(
    Long deliveryId,
    Long invoiceId,
    String recipientEmail,
    String subject,
    String tokenPrefix,
    LocalDateTime sentAt,
    boolean sandboxMode
) {
}

