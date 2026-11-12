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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Authoritative record of a shareable public payment link for an approved invoice.
 *
 * SECURITY & COMPLIANCE RULES:
 *   - Never stores raw link tokens; stores only SHA-256 token_hash and a short non-usable prefix.
 *   - Bound to a specific invoice_snapshot_hash so post-edit or revoked approvals cannot be paid.
 *   - active_invoice_id permits at most one active (non-revoked) link per invoice while
 *     keeping historical links for auditability.
 */
@Entity
@Table(
    name = "payment_links",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payment_links_token_hash", columnNames = "token_hash"),
        @UniqueConstraint(name = "uq_payment_links_active_invoice", columnNames = "active_invoice_id")
    },
    indexes = {
        @Index(name = "idx_payment_links_invoice_id", columnList = "invoice_id"),
        @Index(name = "idx_payment_links_user_id", columnList = "user_id"),
        @Index(name = "idx_payment_links_expires_at", columnList = "expires_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Invoice reference is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "invoice_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_payment_links_invoice")
    )
    @ToString.Exclude
    private Invoice invoice;

    @NotNull(message = "Seller owner reference is required")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "user_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_payment_links_user")
    )
    @ToString.Exclude
    private User user;

    @Column(name = "active_invoice_id")
    private Long activeInvoiceId;

    @NotBlank(message = "Token hash is required")
    @Size(min = 64, max = 64, message = "Token hash must be a 64-character SHA-256 hex digest")
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "Token hash must be lowercase hexadecimal")
    @ToString.Exclude
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @NotBlank(message = "Token prefix is required")
    @Size(max = 16)
    @Column(name = "token_prefix", nullable = false, length = 16)
    private String tokenPrefix;

    @NotBlank(message = "Invoice snapshot hash is required")
    @Size(min = 64, max = 64, message = "Invoice snapshot hash must be a 64-character SHA-256 hex digest")
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "Invoice snapshot hash must be lowercase hexadecimal")
    @Column(name = "invoice_snapshot_hash", nullable = false, length = 64)
    private String invoiceSnapshotHash;

    @NotNull(message = "Expiration timestamp is required")
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Size(max = 100)
    @Column(name = "revocation_reason", length = 100)
    private String revocationReason;

    @Min(0)
    @Builder.Default
    @Column(name = "access_count", nullable = false)
    private int accessCount = 0;

    @Column(name = "last_accessed_at")
    private LocalDateTime lastAccessedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
        syncActiveInvoiceSlot();
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        Objects.requireNonNull(now, "Comparison timestamp is required");
        return this.expiresAt == null || !this.expiresAt.isAfter(now);
    }

    public boolean isUsableAt(LocalDateTime now) {
        return !isRevoked() && !isExpired(now);
    }

    /**
     * Marks this payment link as revoked and releases active_invoice_id so a replacement link
     * can be generated while preserving historical link records.
     */
    public void revoke(LocalDateTime revokedAt, String reason) {
        Objects.requireNonNull(revokedAt, "Revocation timestamp is required");
        if (this.revokedAt == null) {
            this.revokedAt = revokedAt;
        }
        if (reason != null && !reason.isBlank()) {
            String trimmed = reason.trim();
            this.revocationReason = trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
        }
        syncActiveInvoiceSlot();
    }

    public void recordAccess(LocalDateTime accessedAt) {
        Objects.requireNonNull(accessedAt, "Access timestamp is required");
        this.lastAccessedAt = accessedAt;
        this.accessCount++;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.accessCount < 0) {
            this.accessCount = 0;
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

    private void syncActiveInvoiceSlot() {
        if (this.revokedAt == null && this.invoice != null) {
            this.activeInvoiceId = this.invoice.getId();
        } else {
            this.activeInvoiceId = null;
        }
    }
}

