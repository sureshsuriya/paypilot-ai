package com.paypilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.dto.ai.AiServiceExtractResponse;
import com.paypilot.dto.ai.AiServiceExtractResponse.RawDraftPayload;
import com.paypilot.dto.ai.AiServiceExtractResponse.TokenUsagePayload;
import com.paypilot.exception.AiServiceUnavailableException;
import com.paypilot.exception.AiUpstreamException;
import com.paypilot.model.AiExtractionLog;
import com.paypilot.model.AuditLog;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.repository.AiExtractionLogRepository;
import com.paypilot.repository.AuditLogRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.service.AiServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiExtractionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private AiExtractionLogRepository extractionLogRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockBean
    private AiServiceClient aiServiceClient;

    @Test
    void extractionRequiresAuthenticationAndValidPrompt() throws Exception {
        mockMvc.perform(post("/api/ai/extract-invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "Create a $750 invoice for Alex"
                ))))
            .andExpect(status().isUnauthorized());

        String token = registerAndGetToken("Auth Guard", "aiguard@paypilot.test");

        mockMvc.perform(post("/api/ai/extract-invoice")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("prompt", "hi"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.prompt").exists());
    }

    @Test
    void extractsDraftWithoutAutoSavingAndLinksUponUserConfirmation() throws Exception {
        String token = registerAndGetToken("Alex Owner", "alexowner@paypilot.test");
        LocalDate expectedDue = LocalDate.now().plusDays(7);
        long initialInvoiceCount = invoiceRepository.count();

        when(aiServiceClient.extractInvoice(
            eq("Create a $750 invoice for Alex for website development. Payment is due in seven days."),
            any(LocalDate.class)
        )).thenReturn(new AiServiceExtractResponse(
            true,
            true,
            "AI-extracted draft — please verify all details before saving",
            "live",
            "gpt-4o",
            new BigDecimal("0.94"),
            "Interpreted '$' as USD and 'in seven days' relative to today.",
            new RawDraftPayload(
                "Alex",
                null,
                "Website development",
                new BigDecimal("750"),
                "usd",
                expectedDue.toString()
            ),
            List.of("clientEmail"),
            Map.of(),
            false,
            new TokenUsagePayload(140, 48)
        ));

        MvcResult extractResult = mockMvc.perform(post("/api/ai/extract-invoice")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "Create a $750 invoice for Alex for website development. Payment is due in seven days."
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.extractionLogId").isNumber())
            .andExpect(jsonPath("$.aiGenerated").value(true))
            .andExpect(jsonPath("$.requiresHumanReview").value(true))
            .andExpect(jsonPath("$.modelUsed").value("gpt-4o"))
            .andExpect(jsonPath("$.readyToSave").value(false))
            .andExpect(jsonPath("$.missingFields[0]").value("clientEmail"))
            .andExpect(jsonPath("$.draft.clientName").value("Alex"))
            .andExpect(jsonPath("$.draft.clientEmail").doesNotExist())
            .andExpect(jsonPath("$.draft.amount").value(750.00))
            .andExpect(jsonPath("$.draft.currency").value("USD"))
            .andExpect(jsonPath("$.draft.dueDate").value(expectedDue.toString()))
            .andReturn();

        // Extraction must NEVER auto-save an Invoice
        assertThat(invoiceRepository.count()).isEqualTo(initialInvoiceCount);

        JsonNode extractJson = objectMapper.readTree(extractResult.getResponse().getContentAsString());
        long extractionLogId = extractJson.get("extractionLogId").asLong();

        AiExtractionLog draftLog = extractionLogRepository.findById(extractionLogId).orElseThrow();
        assertThat(draftLog.getValidatedByUser()).isFalse();
        assertThat(draftLog.getInvoice()).isNull();
        assertThat(draftLog.getPromptTokens()).isEqualTo(140);
        assertThat(draftLog.getCompletionTokens()).isEqualTo(48);

        List<AuditLog> aiAuditLogs = auditLogRepository
            .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("AI_EXTRACTION", extractionLogId);
        assertThat(aiAuditLogs).extracting(AuditLog::getAction).containsExactly("AI_INVOICE_EXTRACTED");

        // Now user completes the missing email and explicitly confirms saving via POST /api/invoices
        MvcResult saveResult = mockMvc.perform(post("/api/invoices")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", "Alex",
                    "clientEmail", "alex@example.com",
                    "description", "Website development",
                    "amount", new BigDecimal("750.00"),
                    "currency", "USD",
                    "dueDate", expectedDue.toString(),
                    "extractionLogId", extractionLogId
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.aiExtracted").value(true))
            .andExpect(jsonPath("$.aiConfidence").value(0.94))
            .andReturn();

        long invoiceId = objectMapper.readTree(saveResult.getResponse().getContentAsString()).get("id").asLong();
        Invoice savedInvoice = invoiceRepository.findById(invoiceId).orElseThrow();
        assertThat(savedInvoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(savedInvoice.getAiExtracted()).isTrue();

        AiExtractionLog confirmedLog = extractionLogRepository.findById(extractionLogId).orElseThrow();
        assertThat(confirmedLog.getValidatedByUser()).isTrue();
        assertThat(confirmedLog.getInvoice().getId()).isEqualTo(invoiceId);

        // Deleting the AI-assisted draft preserves AiExtractionLog without FK errors
        mockMvc.perform(delete("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());

        AiExtractionLog postDeleteLog = extractionLogRepository.findById(extractionLogId).orElseThrow();
        assertThat(postDeleteLog.getInvoice()).isNull();
    }

    @Test
    void javaBackendAuthoritativelyFlagsInvalidAiFieldsEvenIfAiServiceDidNot() throws Exception {
        String token = registerAndGetToken("Strict Validator", "strict@paypilot.test");

        when(aiServiceClient.extractInvoice(any(), any(LocalDate.class)))
            .thenReturn(new AiServiceExtractResponse(
                true,
                true,
                "AI-extracted draft — please verify all details before saving",
                "live",
                "gpt-4o",
                new BigDecimal("0.60"),
                null,
                new RawDraftPayload(
                    "Alex",
                    "bad-email-format",
                    "Consulting",
                    new BigDecimal("-25.00"),
                    "US",
                    LocalDate.now().minusDays(2).toString()
                ),
                List.of(),
                Map.of(),
                true, // intentionally wrong flag from upstream to verify Java overrides it
                new TokenUsagePayload(90, 30)
            ));

        mockMvc.perform(post("/api/ai/extract-invoice")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "Create an invoice with bad values"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.readyToSave").value(false))
            .andExpect(jsonPath("$.validationErrors.clientEmail").exists())
            .andExpect(jsonPath("$.validationErrors.amount").exists())
            .andExpect(jsonPath("$.validationErrors.currency").exists())
            .andExpect(jsonPath("$.validationErrors.dueDate").exists());
    }

    @Test
    void returnsExplicit502Or503WhenAiServiceFailsWithoutSilentFallback() throws Exception {
        String token = registerAndGetToken("Error User", "erruser@paypilot.test");

        when(aiServiceClient.extractInvoice(any(), any(LocalDate.class)))
            .thenThrow(new AiUpstreamException("AI orchestration service returned status 502"));

        mockMvc.perform(post("/api/ai/extract-invoice")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "Create a $750 invoice for Alex"
                ))))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.status").value(502));

        when(aiServiceClient.extractInvoice(any(), any(LocalDate.class)))
            .thenThrow(new AiServiceUnavailableException("AI orchestration service is unreachable."));

        mockMvc.perform(post("/api/ai/extract-invoice")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "prompt", "Create a $750 invoice for Alex"
                ))))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503));
    }

    private String registerAndGetToken(String name, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", name,
                    "email", email,
                    "password", "SecretPass123!"
                ))))
            .andExpect(status().isCreated())
            .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("token").asText();
    }
}

