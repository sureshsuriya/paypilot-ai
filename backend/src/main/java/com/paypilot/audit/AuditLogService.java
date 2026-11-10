package com.paypilot.audit;

import com.paypilot.model.AuditActor;
import com.paypilot.model.AuditLog;
import com.paypilot.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Append-only audit logging service for financial, payment, and AI events.
 *
 * COMPLIANCE GUARANTEES:
 *   - Append-only: exposes create and read operations only (no update/delete).
 *   - Secret hygiene: sensitive keys are automatically redacted before persistence.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
        "password", "secret", "token", "api_key", "apikey",
        "authorization", "credential", "private_key", "card", "cvv"
    );

    private final AuditLogRepository auditLogRepository;

    /**
     * Records an immutable audit entry inside the current transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditLog logEvent(
            Long userId,
            String entityType,
            Long entityId,
            String action,
            AuditActor actor,
            Map<String, Object> details,
            String ipAddress) {
        return persistEntry(userId, entityType, entityId, action, actor, details, ipAddress);
    }

    /**
     * Convenience helper for financial and AI workflow events without IP metadata.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditLog logFinancialEvent(
            Long userId,
            String entityType,
            Long entityId,
            String action,
            AuditActor actor,
            Map<String, Object> details) {
        return persistEntry(userId, entityType, entityId, action, actor, details, null);
    }

    /**
     * Records an immutable audit entry in an independent transaction so failed
     * payment/capture attempts remain logged even if the caller transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog logIndependentEvent(
            Long userId,
            String entityType,
            Long entityId,
            String action,
            AuditActor actor,
            Map<String, Object> details,
            String ipAddress) {
        return persistEntry(userId, entityType, entityId, action, actor, details, ipAddress);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getEntityHistory(String entityType, Long entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getUserHistory(Long userId) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getRecentEvents() {
        return auditLogRepository.findTop50ByOrderByCreatedAtDesc();
    }

    private AuditLog persistEntry(
            Long userId,
            String entityType,
            Long entityId,
            String action,
            AuditActor actor,
            Map<String, Object> details,
            String ipAddress) {

        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("Audit entityType must not be blank");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("Audit action must not be blank");
        }
        if (actor == null) {
            throw new IllegalArgumentException("Audit actor must not be null");
        }

        AuditLog entry = AuditLog.builder()
            .userId(userId)
            .entityType(entityType.trim())
            .entityId(entityId)
            .action(action.trim())
            .actor(actor)
            .details(sanitizeDetails(details))
            .ipAddress(ipAddress)
            .build();

        return auditLogRepository.save(entry);
    }

    private Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (isSensitiveKey(key)) {
                sanitized.put(key, "[REDACTED]");
            } else if (entry.getValue() instanceof Map<?, ?> nestedMap) {
                sanitized.put(key, sanitizeNestedMap(nestedMap));
            } else {
                sanitized.put(key, entry.getValue());
            }
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private Map<String, Object> sanitizeNestedMap(Map<?, ?> rawMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().toString();
            if (isSensitiveKey(key)) {
                result.put(key, "[REDACTED]");
            } else if (entry.getValue() instanceof Map<?, ?> deeperMap) {
                result.put(key, sanitizeNestedMap(deeperMap));
            } else {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        for (String fragment : SENSITIVE_KEY_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}

