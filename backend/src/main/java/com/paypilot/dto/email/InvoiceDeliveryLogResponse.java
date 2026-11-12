package com.paypilot.dto.email;

import com.paypilot.model.InvoiceDelivery;

import java.time.LocalDateTime;

/**
 * Safe audit projection of past invoice delivery attempts.
 */
public record InvoiceDeliveryLogResponse(
    Long id,
    Long invoiceId,
    String channel,
    String recipient,
    String subject,
    String status,
    String failureReason,
    LocalDateTime createdAt
) {
    public static InvoiceDeliveryLogResponse from(InvoiceDelivery delivery) {
        return new InvoiceDeliveryLogResponse(
            delivery.getId(),
            delivery.getInvoice().getId(),
            delivery.getChannel(),
            delivery.getRecipient(),
            delivery.getSubject(),
            delivery.getStatus(),
            delivery.getFailureReason(),
            delivery.getCreatedAt()
        );
    }
}

