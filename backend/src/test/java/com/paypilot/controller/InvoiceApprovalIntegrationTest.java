package com.paypilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.model.AuditLog;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentStatus;
import com.paypilot.repository.AuditLogRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InvoiceApprovalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void approvesDraftWithMatchingSnapshotAndRejectsWrongHashOrMissingConfirmation() throws Exception {
        String token = registerAndGetToken("Approver One", "approver1@paypilot.test");
        JsonNode created = createDraft(token);
        long invoiceId = created.get("id").asLong();
        String snapshotHash = created.get("snapshotHash").asText();
        assertThat(snapshotHash).hasSize(64);

        // Missing / false confirmation -> 400 Bad Request
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", snapshotHash,
                    "confirmApproval", false
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.confirmApproval").exists());

        // Wrong snapshot hash -> 409 Conflict, remains DRAFT
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", "0".repeat(64),
                    "confirmApproval", true
                ))))
            .andExpect(status().isConflict());

        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.DRAFT);

        // Valid owner approval -> 200 OK
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", snapshotHash,
                    "confirmApproval", true
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvedAt").isNotEmpty())
            .andExpect(jsonPath("$.approvedByUserId").isNumber())
            .andExpect(jsonPath("$.approvedSnapshotHash").value(snapshotHash));

        List<AuditLog> logs = auditLogRepository
            .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", invoiceId);
        assertThat(logs).extracting(AuditLog::getAction).contains("INVOICE_APPROVED");
    }

    @Test
    void enforcesOwnershipOnApproveAndRevoke() throws Exception {
        String ownerToken = registerAndGetToken("Owner A", "ownera@paypilot.test");
        String otherToken = registerAndGetToken("Intruder B", "intruderb@paypilot.test");

        JsonNode created = createDraft(ownerToken);
        long invoiceId = created.get("id").asLong();
        String snapshotHash = created.get("snapshotHash").asText();

        mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", snapshotHash,
                    "confirmApproval", true
                ))))
            .andExpect(status().isForbidden());

        // Owner approves cleanly
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", snapshotHash,
                    "confirmApproval", true
                ))))
            .andExpect(status().isOk());

        // Other user cannot revoke
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/revoke-approval")
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isForbidden());

        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.APPROVED);
    }

    @Test
    void revokingApprovalClearsMetadataAndInvalidatesUncapturedOrdersLocally() throws Exception {
        String token = registerAndGetToken("Revoker", "revoker@paypilot.test");
        JsonNode created = createDraft(token);
        long invoiceId = created.get("id").asLong();
        String approvedHash = created.get("snapshotHash").asText();

        mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", approvedHash,
                    "confirmApproval", true
                ))))
            .andExpect(status().isOk());

        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
        Payment uncapturedOrder = paymentRepository.saveAndFlush(Payment.builder()
            .invoice(invoice)
            .paypalOrderId("PAYPAL-ORDER-UNCAP-1")
            .amount(invoice.getAmount())
            .currency(invoice.getCurrency())
            .status(PaymentStatus.CREATED)
            .invoiceSnapshotHash(approvedHash)
            .build());

        mockMvc.perform(post("/api/invoices/" + invoiceId + "/revoke-approval")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.approvedAt").doesNotExist())
            .andExpect(jsonPath("$.approvedByUserId").doesNotExist())
            .andExpect(jsonPath("$.approvedSnapshotHash").doesNotExist());

        Payment reloadedOrder = paymentRepository.findById(uncapturedOrder.getId()).orElseThrow();
        assertThat(reloadedOrder.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(reloadedOrder.getInvoiceSnapshotHash()).isEqualTo(approvedHash);

        List<AuditLog> logs = auditLogRepository
            .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", invoiceId);
        assertThat(logs).extracting(AuditLog::getAction).contains("INVOICE_APPROVAL_REVOKED");
    }

    @Test
    void editingApprovedInvoiceInvalidatesApprovalAndInvalidatesUncapturedOrder() throws Exception {
        String token = registerAndGetToken("Editor", "editor@paypilot.test");
        JsonNode created = createDraft(token);
        long invoiceId = created.get("id").asLong();
        String originalHash = created.get("snapshotHash").asText();

        mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", originalHash,
                    "confirmApproval", true
                ))))
            .andExpect(status().isOk());

        Invoice approvedInvoice = invoiceRepository.findById(invoiceId).orElseThrow();
        Payment staleOrder = paymentRepository.saveAndFlush(Payment.builder()
            .invoice(approvedInvoice)
            .paypalOrderId("PAYPAL-STALE-ORDER-99")
            .amount(approvedInvoice.getAmount())
            .currency(approvedInvoice.getCurrency())
            .status(PaymentStatus.APPROVED)
            .invoiceSnapshotHash(originalHash)
            .build());

        // Edit the amount while APPROVED -> must invalidate approval and return to DRAFT
        mockMvc.perform(put("/api/invoices/" + invoiceId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", "Alex",
                    "clientEmail", "alex@example.com",
                    "description", "Website development",
                    "amount", new BigDecimal("900.00"),
                    "currency", "USD",
                    "dueDate", LocalDate.now().plusDays(7).toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.amount").value(900.00))
            .andExpect(jsonPath("$.approvedAt").doesNotExist())
            .andExpect(jsonPath("$.approvedByUserId").doesNotExist())
            .andExpect(jsonPath("$.approvedSnapshotHash").doesNotExist())
            .andExpect(jsonPath("$.snapshotHash").value(org.hamcrest.Matchers.not(originalHash)));

        // Previous uncaptured order is marked CANCELLED locally and carries the old hash for Step D guard
        Payment reloadedStaleOrder = paymentRepository.findById(staleOrder.getId()).orElseThrow();
        assertThat(reloadedStaleOrder.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(reloadedStaleOrder.getInvoiceSnapshotHash()).isEqualTo(originalHash);

        List<AuditLog> logs = auditLogRepository
            .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", invoiceId);
        assertThat(logs)
            .extracting(AuditLog::getAction)
            .contains("INVOICE_UPDATED", "INVOICE_APPROVAL_INVALIDATED");
    }

    @Test
    void protectsPendingAndCapturedPaymentsAndBlocksNonEditableStatuses() throws Exception {
        String token = registerAndGetToken("Protector", "protector@paypilot.test");

        for (InvoiceStatus blockedStatus : List.of(
                InvoiceStatus.PAID, InvoiceStatus.SENT, InvoiceStatus.OVERDUE, InvoiceStatus.CANCELLED)) {
            JsonNode draft = createDraft(token);
            long id = draft.get("id").asLong();
            Invoice inv = invoiceRepository.findById(id).orElseThrow();
            inv.setStatus(blockedStatus);
            invoiceRepository.saveAndFlush(inv);

            mockMvc.perform(put("/api/invoices/" + id)
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
                .andExpect(status().isConflict());
        }

        // APPROVED invoice with PENDING payment must reject edit and revoke without altering PENDING payment
        JsonNode pendingDraft = createDraft(token);
        long pendingInvId = pendingDraft.get("id").asLong();
        String pendingHash = pendingDraft.get("snapshotHash").asText();

        mockMvc.perform(post("/api/invoices/" + pendingInvId + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", pendingHash,
                    "confirmApproval", true
                ))))
            .andExpect(status().isOk());

        Invoice pendingInvoice = invoiceRepository.findById(pendingInvId).orElseThrow();
        Payment pendingPayment = paymentRepository.saveAndFlush(Payment.builder()
            .invoice(pendingInvoice)
            .paypalOrderId("PAYPAL-ORDER-PENDING-1")
            .paypalCaptureId("PAYPAL-CAP-PENDING-1")
            .amount(pendingInvoice.getAmount())
            .currency(pendingInvoice.getCurrency())
            .status(PaymentStatus.PENDING)
            .invoiceSnapshotHash(pendingHash)
            .build());

        mockMvc.perform(put("/api/invoices/" + pendingInvId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", "Alex Changed",
                    "clientEmail", "alex@example.com",
                    "description", "Website development",
                    "amount", new BigDecimal("800.00"),
                    "currency", "USD",
                    "dueDate", LocalDate.now().plusDays(7).toString()
                ))))
            .andExpect(status().isConflict());

        mockMvc.perform(post("/api/invoices/" + pendingInvId + "/revoke-approval")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());

        assertThat(paymentRepository.findById(pendingPayment.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.PENDING);

        // Captured payment also remains untouched
        JsonNode capturedDraft = createDraft(token);
        long capturedInvId = capturedDraft.get("id").asLong();
        Invoice capturedInvoice = invoiceRepository.findById(capturedInvId).orElseThrow();
        capturedInvoice.setStatus(InvoiceStatus.APPROVED);
        invoiceRepository.saveAndFlush(capturedInvoice);

        Payment capturedPayment = paymentRepository.saveAndFlush(Payment.builder()
            .invoice(capturedInvoice)
            .paypalOrderId("PAYPAL-ORDER-CAP-1")
            .paypalCaptureId("PAYPAL-CAP-1")
            .amount(capturedInvoice.getAmount())
            .currency(capturedInvoice.getCurrency())
            .status(PaymentStatus.CAPTURED)
            .build());

        mockMvc.perform(post("/api/invoices/" + capturedInvId + "/revoke-approval")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());

        assertThat(paymentRepository.findById(capturedPayment.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.CAPTURED);
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

    private JsonNode createDraft(String token) throws Exception {
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

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}

