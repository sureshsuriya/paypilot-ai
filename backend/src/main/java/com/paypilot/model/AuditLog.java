package com.paypilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Immutable append-only audit log entry for financial and AI actions.
 *
 * COMPLIANCE RULE:
 *   Audit logs are append-only. Updates are blocked at both the Hibernate
 *   (@Immutable) and JPA lifecycle (@PreUpdate) levels.
 */
@Entity
@Immutable
@Table(
    name = "audit_logs",
    indexes = {
        @Index(name = "idx_audit_user_id", columnList = "user_id"),
        @Index(name = "idx_audit_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_created_at", columnList = "created_at")
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @NotBlank(message = "Entity type is required")
    @Size(max = 100)
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @NotBlank(message = "Action is required")
    @Size(max = 100)
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @NotNull(message = "Actor is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, length = 50)
    private AuditActor actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "json")
    private Map<String, Object> details;

    @Size(max = 45)
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void preventUpdate() {
        throw new IllegalStateException("AuditLog entries are append-only and cannot be updated.");
    }
}

