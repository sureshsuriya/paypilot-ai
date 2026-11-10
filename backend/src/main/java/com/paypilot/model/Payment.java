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
    indexes = {
        @Index(name = "idx_payments_invoice_id", columnList = "invoice_id"),
        @Index(name = "idx_payments_order_id", columnList = "paypal_order_id"),
        @Index(name = "idx_payments_capture_id", columnList = "paypal_capture_id")
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

    @NotBlank(message = "PayPal order ID is required")
    @Size(max = 255)
    @Column(name = "paypal_order_id", nullable = false, length = 255)
    private String paypalOrderId;

    @Size(max = 255)
    @Column(name = "paypal_capture_id", length = 255)
    private String paypalCaptureId;

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

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.currency == null || this.currency.isBlank()) {
            this.currency = "USD";
        }
        if (this.status == null) {
            this.status = PaymentStatus.CREATED;
        }
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

