package com.paypilot.repository;

import com.paypilot.model.AiExtractionLog;
import com.paypilot.model.ExtractionMethod;
import com.paypilot.model.Invoice;
import com.paypilot.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AiExtractionLogRepositoryTest {

    @Autowired
    private AiExtractionLogRepository extractionLogRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsDraftExtractionBeforeInvoiceConfirmationAndLinksAfterSave() {
        AiExtractionLog draftLog = AiExtractionLog.builder()
            .modelUsed("gpt-4o")
            .promptTokens(420)
            .completionTokens(115)
            .confidenceScore(new BigDecimal("0.9200"))
            .validatedByUser(false)
            .extractionMethod(ExtractionMethod.TEXT)
            .build();

        AiExtractionLog savedDraft = extractionLogRepository.saveAndFlush(draftLog);
        assertThat(savedDraft.getInvoice()).isNull();
        assertThat(savedDraft.getValidatedByUser()).isFalse();

        User user = userRepository.saveAndFlush(User.builder()
            .email("aiuser@paypilot.test")
            .name("AI User")
            .passwordHash("hashed")
            .build());

        Invoice confirmedInvoice = invoiceRepository.saveAndFlush(Invoice.builder()
            .user(user)
            .invoiceNumber("INV-AI-001")
            .clientName("AI Client")
            .clientEmail("client@ai.test")
            .amount(new BigDecimal("880.00"))
            .aiExtracted(true)
            .aiConfidence(new BigDecimal("0.9200"))
            .build());

        savedDraft.setInvoice(confirmedInvoice);
        savedDraft.setValidatedByUser(true);
        extractionLogRepository.saveAndFlush(savedDraft);
        entityManager.clear();

        List<AiExtractionLog> logs = extractionLogRepository.findByInvoiceIdOrderByCreatedAtDesc(confirmedInvoice.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getModelUsed()).isEqualTo("gpt-4o");
        assertThat(logs.get(0).getValidatedByUser()).isTrue();
        assertThat(logs.get(0).getConfidenceScore()).isEqualByComparingTo("0.9200");
        assertThat(logs.get(0).getExtractionMethod()).isEqualTo(ExtractionMethod.TEXT);
    }
}

