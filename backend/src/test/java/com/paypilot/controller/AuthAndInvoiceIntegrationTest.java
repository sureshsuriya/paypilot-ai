package com.paypilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.model.AuditLog;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.repository.AuditLogRepository;
import com.paypilot.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthAndInvoiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void healthEndpointIsPublicAndInvoicesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/api/invoices"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/invoices")
                .header("Authorization", "Bearer invalid.jwt.token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void registerLoginAndRejectDuplicateOrBadCredentials() throws Exception {
        Map<String, Object> registerPayload = Map.of(
            "name", "Suresh Test",
            "email", "Suresh@PayPilot.Test",
            "password", "StrongPass123!"
        );

        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerPayload)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.user.email").value("suresh@paypilot.test"))
            .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
            .andReturn();

        assertThat(regResult.getResponse().getContentAsString()).doesNotContain("StrongPass123!");

        // Duplicate registration -> 409 Conflict
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerPayload)))
            .andExpect(status().isConflict());

        // Valid login -> 200 OK
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "suresh@paypilot.test",
                    "password", "StrongPass123!"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty());

        // Bad password -> 401 Unauthorized
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "suresh@paypilot.test",
                    "password", "WrongPassword!"
                ))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void fullDraftInvoiceCrudLifecycleWithAuditLogging() throws Exception {
        String token = registerAndGetToken("Alice Freelancer", "alice@paypilot.test");
        LocalDate dueDate = LocalDate.now().plusDays(7);

        // Include a spoofed userId to verify the backend ignores client-supplied ownership fields
        Map<String, Object> createPayload = Map.of(
            "userId", 99999L,
            "clientName", "Alex Morgan",
            "clientEmail", "Alex@Client.COM",
            "description", "Website development milestone",
            "amount", new BigDecimal("750.00"),
            "currency", "usd",
            "dueDate", dueDate.toString()
        );

        MvcResult createResult = mockMvc.perform(post("/api/invoices")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createPayload)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.invoiceNumber").value(org.hamcrest.Matchers.startsWith("INV-")))
            .andExpect(jsonPath("$.clientName").value("Alex Morgan"))
            .andExpect(jsonPath("$.clientEmail").value("alex@client.com"))
            .andExpect(jsonPath("$.amount").value(750.00))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        long invoiceId = createdJson.get("id").asLong();

        // Confirm owner is Alice, not spoofed userId 99999
        Invoice persisted = invoiceRepository.findById(invoiceId).orElseThrow();
        assertThat(persisted.getUser().getEmail()).isEqualTo("alice@paypilot.test");

        // List invoices
        mockMvc.perform(get("/api/invoices")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(invoiceId));

        // Filter by status
        mockMvc.perform(get("/api/invoices?status=DRAFT")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        // Get single invoice
        mockMvc.perform(get("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.invoiceNumber").value(persisted.getInvoiceNumber()));

        // Update DRAFT invoice
        LocalDate updatedDue = LocalDate.now().plusDays(14);
        Map<String, Object> updatePayload = Map.of(
            "clientName", "Alex Morgan Updated",
            "clientEmail", "billing@client.com",
            "description", "Website development + hosting setup",
            "amount", new BigDecimal("825.50"),
            "currency", "USD",
            "dueDate", updatedDue.toString()
        );

        mockMvc.perform(put("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatePayload)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clientName").value("Alex Morgan Updated"))
            .andExpect(jsonPath("$.amount").value(825.50))
            .andExpect(jsonPath("$.dueDate").value(updatedDue.toString()));

        // Delete DRAFT invoice
        mockMvc.perform(delete("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent());

        assertThat(invoiceRepository.findById(invoiceId)).isEmpty();

        // Verify immutable audit trail recorded CREATE, UPDATE, and DELETE
        List<AuditLog> logs = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", invoiceId);
        assertThat(logs)
            .extracting(AuditLog::getAction)
            .containsExactly("INVOICE_DELETED", "INVOICE_UPDATED", "INVOICE_CREATED");
    }

    @Test
    void rejectsInvalidInvoiceInputAndMissingInvoice() throws Exception {
        String token = registerAndGetToken("Val User", "val@paypilot.test");

        Map<String, Object> invalidPayload = Map.of(
            "clientName", " ",
            "clientEmail", "bad-email",
            "description", "",
            "amount", new BigDecimal("0.00"),
            "currency", "US",
            "dueDate", LocalDate.now().minusDays(1).toString()
        );

        mockMvc.perform(post("/api/invoices")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidPayload)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.fieldErrors.clientName").exists())
            .andExpect(jsonPath("$.fieldErrors.clientEmail").exists())
            .andExpect(jsonPath("$.fieldErrors.description").exists())
            .andExpect(jsonPath("$.fieldErrors.amount").exists())
            .andExpect(jsonPath("$.fieldErrors.currency").exists())
            .andExpect(jsonPath("$.fieldErrors.dueDate").exists());

        // Missing invoice -> 404
        mockMvc.perform(get("/api/invoices/999999")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void enforcesOwnershipAcrossUsers() throws Exception {
        String ownerToken = registerAndGetToken("Owner User", "owner1@paypilot.test");
        String otherToken = registerAndGetToken("Other User", "other1@paypilot.test");

        long invoiceId = createDraftAndGetId(ownerToken);

        // Other user cannot list owner's invoice
        mockMvc.perform(get("/api/invoices")
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // Other user gets 403 Forbidden on GET, PUT, DELETE
        mockMvc.perform(get("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403));

        Map<String, Object> updatePayload = Map.of(
            "clientName", "Hijack",
            "clientEmail", "hijack@test.com",
            "description", "Attempted hijack",
            "amount", new BigDecimal("1.00"),
            "currency", "USD",
            "dueDate", LocalDate.now().plusDays(5).toString()
        );

        mockMvc.perform(put("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatePayload)))
            .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void blocksUpdateAndDeleteOnNonDraftInvoices() throws Exception {
        String token = registerAndGetToken("Draft Guard", "guard@paypilot.test");
        long invoiceId = createDraftAndGetId(token);

        // Simulate a SENT (or PAID) invoice in the repository
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
        invoice.setStatus(InvoiceStatus.SENT);
        invoiceRepository.saveAndFlush(invoice);

        Map<String, Object> updatePayload = Map.of(
            "clientName", "Changed Name",
            "clientEmail", "changed@test.com",
            "description", "Changed desc",
            "amount", new BigDecimal("999.00"),
            "currency", "USD",
            "dueDate", LocalDate.now().plusDays(10).toString()
        );

        mockMvc.perform(put("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatePayload)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(delete("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
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

    private long createDraftAndGetId(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invoices")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", "Alex",
                    "clientEmail", "alex@example.com",
                    "description", "Website development",
                    "amount", new BigDecimal("750.00"),
                    "currency", "USD",
                    "dueDate", LocalDate.now().plusDays(7).toString()
                ))))
            .andExpect(status().isCreated())
            .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("id").asLong();
    }
}
