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
    Long customerId,
    String invoiceNumber,
    String paypalInvoiceId,
    String clientName,
    String clientEmail,
    String clientPhone,
    String description,
    BigDecimal amount,
    String currency,
    InvoiceStatus status,
    LocalDate dueDate,
    LocalDate issuedAt,
    LocalDateTime paidAt,
    Boolean aiExtracted,
    BigDecimal aiConfidence,
    String snapshotHash,
    LocalDateTime approvedAt,
    Long approvedByUserId,
    String approvedSnapshotHash,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static InvoiceResponse from(Invoice invoice, String snapshotHash) {
        Long customerId = invoice.getCustomer() != null ? invoice.getCustomer().getId() : null;
        return new InvoiceResponse(
            invoice.getId(),
            customerId,
            invoice.getInvoiceNumber(),
            invoice.getPaypalInvoiceId(),
            invoice.getClientName(),
            invoice.getClientEmail(),
            invoice.getClientPhone(),
            invoice.getDescription(),
            invoice.getAmount(),
            invoice.getCurrency(),
            invoice.getStatus(),
            invoice.getDueDate(),
            invoice.getIssuedAt(),
            invoice.getPaidAt(),
            invoice.getAiExtracted(),
            invoice.getAiConfidence(),
            snapshotHash,
            invoice.getApprovedAt(),
            invoice.getApprovedByUserId(),
            invoice.getApprovedSnapshotHash(),
            invoice.getCreatedAt(),
            invoice.getUpdatedAt()
        );
    }
}
