package com.paypilot.audit;

import com.paypilot.model.AuditActor;
import com.paypilot.model.AuditLog;
import com.paypilot.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(AuditLogService.class)
class AuditLogServiceTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void logsFinancialEventAndRedactsSensitiveKeys() {
        AuditLog created = auditLogService.logEvent(
            42L,
            "PAYMENT",
            101L,
            "PAYMENT_CAPTURED",
            AuditActor.USER,
            Map.of(
                "paypalOrderId", "ORDER-123",
                "amount", "250.00",
                "clientSecret", "should-never-appear",
                "nested", Map.of("accessToken", "secret-jwt")
            ),
            "127.0.0.1"
        );
        entityManager.flush();
        entityManager.clear();

        List<AuditLog> history = auditLogService.getEntityHistory("PAYMENT", 101L);
        assertThat(history).hasSize(1);
        AuditLog saved = history.get(0);
        assertThat(saved.getId()).isEqualTo(created.getId());
        assertThat(saved.getActor()).isEqualTo(AuditActor.USER);
        assertThat(saved.getDetails())
            .containsEntry("paypalOrderId", "ORDER-123")
            .containsEntry("clientSecret", "[REDACTED]");

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) saved.getDetails().get("nested");
        assertThat(nested).containsEntry("accessToken", "[REDACTED]");
    }

    @Test
    void queriesUserAndRecentEvents() {
        auditLogService.logFinancialEvent(7L, "INVOICE", 1L, "INVOICE_CREATED", AuditActor.USER, Map.of("amount", "100.00"));
        auditLogService.logFinancialEvent(7L, "INVOICE", 1L, "AI_PROPOSAL_GENERATED", AuditActor.AI_AGENT, Map.of("model", "gpt-4o"));
        auditLogService.logFinancialEvent(null, "SYSTEM", null, "HEALTH_CHECK", AuditActor.SYSTEM, null);
        entityManager.flush();

        assertThat(auditLogService.getUserHistory(7L)).hasSize(2);
        assertThat(auditLogService.getRecentEvents()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(auditLogRepository.count()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void rejectsBlankActionOrNullActor() {
        assertThatThrownBy(() -> auditLogService.logFinancialEvent(1L, "INVOICE", 1L, " ", AuditActor.USER, null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> auditLogService.logFinancialEvent(1L, "INVOICE", 1L, "CREATED", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

