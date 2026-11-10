package com.paypilot.dto.invoice;

import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * API projection for Invoice records. Never exposes JPA entities or User credentials.
 */
public record InvoiceResponse(
    Long id,
    String invoiceNumber,
    String paypalInvoiceId,
    String clientName,
    String clientEmail,
    String description,
    BigDecimal amount,
    String currency,
    InvoiceStatus status,
    LocalDate dueDate,
    LocalDate issuedAt,
    LocalDateTime paidAt,
    Boolean aiExtracted,
    BigDecimal aiConfidence,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(
            invoice.getId(),
            invoice.getInvoiceNumber(),
            invoice.getPaypalInvoiceId(),
            invoice.getClientName(),
            invoice.getClientEmail(),
            invoice.getDescription(),
            invoice.getAmount(),
            invoice.getCurrency(),
            invoice.getStatus(),
            invoice.getDueDate(),
            invoice.getIssuedAt(),
            invoice.getPaidAt(),
            invoice.getAiExtracted(),
            invoice.getAiConfidence(),
            invoice.getCreatedAt(),
            invoice.getUpdatedAt()
        );
    }
}

