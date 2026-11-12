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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Durable pre-HTTP intent recording an unresolved PayPal order creation attempt.
 * Preserves stable idempotency keys across timeouts and backend crashes while keeping
 * V1's payments.paypal_order_id NOT NULL constraint untouched.
 */
@Entity
@Table(
    name = "payment_order_intents",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_poi_idempotency_key", columnNames = "idempotency_key"),
        @UniqueConstraint(name = "uq_poi_active_invoice", columnNames = "active_invoice_id")
    },
    indexes = {
        @Index(name = "idx_poi_invoice_snapshot", columnList = "invoice_id, invoice_snapshot_hash")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentOrderIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "invoice_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_poi_invoice")
    )
    @ToString.Exclude
    private Invoice invoice;

    @NotBlank
    @Size(max = 64)
    @Column(name = "invoice_snapshot_hash", nullable = false, length = 64)
    private String invoiceSnapshotHash;

    @NotBlank
    @Size(max = 100)
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 13, fraction = 2)
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @NotBlank
    @Size(min = 3, max = 10)
    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", nullable = false, length = 30)
    private PaymentOrderIntentStatus status = PaymentOrderIntentStatus.PENDING;

    @Column(name = "active_invoice_id")
    private Long activeInvoiceId;

    @Size(max = 255)
    @Column(name = "paypal_order_id", length = 255)
    private String paypalOrderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void setStatus(PaymentOrderIntentStatus status) {
        this.status = status;
        syncActiveSlot();
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.status == null) {
            this.status = PaymentOrderIntentStatus.PENDING;
        }
        syncActiveSlot();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        syncActiveSlot();
        this.updatedAt = LocalDateTime.now();
    }

    private void syncActiveSlot() {
        if (this.status == PaymentOrderIntentStatus.PENDING && this.invoice != null) {
            this.activeInvoiceId = this.invoice.getId();
        } else {
            this.activeInvoiceId = null;
        }
    }
}

