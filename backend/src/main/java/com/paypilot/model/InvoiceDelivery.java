package com.paypilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Audit entity recording seller-triggered invoice delivery attempts (Email).
 * Never stores raw secret payment-link tokens or mail server credentials.
 */
@Entity
@Table(
    name = "invoice_deliveries",
    indexes = {
        @Index(name = "idx_deliveries_invoice_id", columnList = "invoice_id"),
        @Index(name = "idx_deliveries_user_id", columnList = "user_id"),
        @Index(name = "idx_deliveries_cooldown", columnList = "invoice_id, channel, status, created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "invoice_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_deliveries_invoice")
    )
    @ToString.Exclude
    private Invoice invoice;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "user_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_deliveries_user")
    )
    @ToString.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "payment_link_id",
        foreignKey = @ForeignKey(name = "fk_deliveries_payment_link")
    )
    @ToString.Exclude
    private PaymentLink paymentLink;

    @NotBlank
    @Size(max = 20)
    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @NotBlank
    @Size(max = 255)
    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Size(max = 255)
    @Column(name = "subject", length = 255)
    private String subject;

    @NotBlank
    @Size(max = 20)
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Size(max = 500)
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}

