package com.paypilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * PayPal payment record linked to an Invoice.
 *
 * COMPLIANCE & DATA MINIMIZATION:
 *   - Stores only verification evidence (orderId, captureId, status, amount, currency).
 *   - Never stores card numbers, bank details, credentials, or unnecessary payer PII.
 *   - All amounts use BigDecimal (DECIMAL(15,2)).
 */
@Entity
@Table(
    name = "payments",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payments_paypal_order_id", columnNames = "paypal_order_id"),
        @UniqueConstraint(name = "uq_payments_paypal_capture_id", columnNames = "paypal_capture_id"),
        @UniqueConstraint(name = "uq_payments_active_invoice", columnNames = "active_invoice_id"),
        @UniqueConstraint(name = "uq_payments_idempotency_key", columnNames = "idempotency_key"),
        @UniqueConstraint(name = "uq_payments_capture_idempotency_key", columnNames = "capture_idempotency_key")
    },
    indexes = {
        @Index(name = "idx_payments_invoice_id", columnList = "invoice_id"),
        @Index(name = "idx_payments_order_id", columnList = "paypal_order_id"),
        @Index(name = "idx_payments_capture_id", columnList = "paypal_capture_id"),
        @Index(name = "idx_payments_invoice_status", columnList = "invoice_id, status"),
        @Index(name = "idx_payments_payment_link_id", columnList = "payment_link_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Invoice reference is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "invoice_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_payments_invoice")
    )
    @ToString.Exclude
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "payment_link_id",
        foreignKey = @ForeignKey(name = "fk_payments_payment_link")
    )
    @ToString.Exclude
    private PaymentLink paymentLink;

    @NotBlank(message = "PayPal order ID is required")
    @Size(max = 255)
    @Column(name = "paypal_order_id", nullable = false, length = 255)
    private String paypalOrderId;

    @Size(max = 255)
    @Column(name = "paypal_capture_id", length = 255)
    private String paypalCaptureId;

    @Size(max = 64)
    @Column(name = "invoice_snapshot_hash", length = 64)
    private String invoiceSnapshotHash;

    @Size(max = 100)
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Size(max = 100)
    @Column(name = "capture_idempotency_key", length = 100)
    private String captureIdempotencyKey;

    @Column(name = "capture_requested_at")
    private LocalDateTime captureRequestedAt;

    @Column(name = "active_invoice_id")
    private Long activeInvoiceId;

    @Size(max = 1000)
    @Column(name = "paypal_approval_url", length = 1000)
    private String paypalApprovalUrl;

    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero")
    @Digits(integer = 13, fraction = 2, message = "Amount must have at most 13 integer digits and 2 decimal places")
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 10)
    @Builder.Default
    @Column(name = "currency", nullable = false, length = 10)
    private String currency = "USD";

    @NotNull
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 50)
    private PaymentStatus status = PaymentStatus.CREATED;

    /**
     * Minimal non-sensitive PayPal verification fields only
     * (e.g., order_id, capture_id, status, amount, currency, sandbox_mode).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "paypal_response_summary", columnDefinition = "json")
    private Map<String, Object> paypalResponseSummary;

    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void setStatus(PaymentStatus status) {
        this.status = status;
        syncActiveInvoiceSlot();
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.currency == null || this.currency.isBlank()) {
            this.currency = "USD";
        }
        if (this.status == null) {
            this.status = PaymentStatus.CREATED;
        }
        syncActiveInvoiceSlot();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        syncActiveInvoiceSlot();
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isActivePaymentSlotStatus() {
        return this.status == PaymentStatus.CREATED
            || this.status == PaymentStatus.APPROVED
            || this.status == PaymentStatus.PENDING
            || this.status == PaymentStatus.CAPTURED;
    }

    private void syncActiveInvoiceSlot() {
        if (isActivePaymentSlotStatus() && this.invoice != null) {
            this.activeInvoiceId = this.invoice.getId();
        } else {
            this.activeInvoiceId = null;
        }
    }
}

