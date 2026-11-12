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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative invoice entity managed by the Spring Boot backend.
 *
 * COMPLIANCE RULE:
 *   - All financial totals use BigDecimal (DECIMAL(15,2)).
 *   - Status PAID may only be set after a verified PayPal capture record exists.
 */
@Entity
@Table(
    name = "invoices",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_invoices_number_user", columnNames = {"invoice_number", "user_id"})
    },
    indexes = {
        @Index(name = "idx_invoices_user_id", columnList = "user_id"),
        @Index(name = "idx_invoices_status", columnList = "status"),
        @Index(name = "idx_invoices_paypal_id", columnList = "paypal_invoice_id"),
        @Index(name = "idx_invoices_approved_by", columnList = "approved_by_user_id"),
        @Index(name = "idx_invoices_payment_lock", columnList = "id, payment_lock_until"),
        @Index(name = "idx_invoices_customer_id", columnList = "customer_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Invoice owner is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "user_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_invoices_user")
    )
    @ToString.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "customer_id",
        foreignKey = @ForeignKey(name = "fk_invoices_customer")
    )
    @ToString.Exclude
    private Customer customer;

    @Size(max = 32)
    @Column(name = "client_phone", length = 32)
    private String clientPhone;

    @Size(max = 255)
    @Column(name = "paypal_invoice_id", length = 255)
    private String paypalInvoiceId;

    @NotBlank(message = "Invoice number is required")
    @Size(max = 100)
    @Column(name = "invoice_number", nullable = false, length = 100)
    private String invoiceNumber;

    @NotBlank(message = "Client name is required")
    @Size(max = 255)
    @Column(name = "client_name", nullable = false, length = 255)
    private String clientName;

    @NotBlank(message = "Client email is required")
    @Email(message = "Client email must be valid")
    @Size(max = 255)
    @Column(name = "client_email", nullable = false, length = 255)
    private String clientEmail;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "description")
    private String description;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
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
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "issued_at")
    private LocalDate issuedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Size(max = 64)
    @Column(name = "approved_snapshot_hash", length = 64)
    private String approvedSnapshotHash;

    @Column(name = "payment_lock_owner", length = 64, insertable = false, updatable = false)
    private String paymentLockOwner;

    @Column(name = "payment_lock_until", insertable = false, updatable = false)
    private LocalDateTime paymentLockUntil;

    @NotNull
    @Builder.Default
    @Column(name = "ai_extracted", nullable = false)
    private Boolean aiExtracted = Boolean.FALSE;

    @DecimalMin(value = "0.0000")
    @DecimalMax(value = "1.0000")
    @Digits(integer = 1, fraction = 4)
    @Column(name = "ai_confidence", precision = 5, scale = 4)
    private BigDecimal aiConfidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    private List<Payment> payments = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.currency == null || this.currency.isBlank()) {
            this.currency = "USD";
        }
        if (this.status == null) {
            this.status = InvoiceStatus.DRAFT;
        }
        if (this.aiExtracted == null) {
            this.aiExtracted = Boolean.FALSE;
        }
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

