package com.paypilot.repository;

import com.paypilot.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Append-only repository for immutable AuditLog entries.
 * Service callers should only insert and query records, never modify or delete.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);

    List<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<AuditLog> findTop50ByOrderByCreatedAtDesc();
}

