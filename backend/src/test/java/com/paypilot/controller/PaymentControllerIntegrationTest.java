package com.paypilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.dto.paypal.PayPalCaptureResult;
import com.paypilot.dto.paypal.PayPalCreateOrderRequest;
import com.paypilot.dto.paypal.PayPalOrderDetailsResult;
import com.paypilot.dto.paypal.PayPalOrderResult;
import com.paypilot.exception.PayPalApiException;
import com.paypilot.exception.PayPalMalformedResponseException;
import com.paypilot.exception.PayPalUnavailableException;
import com.paypilot.model.AuditLog;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentOrderIntent;
import com.paypilot.model.PaymentOrderIntentStatus;
import com.paypilot.model.PaymentStatus;
import com.paypilot.repository.AiExtractionLogRepository;
import com.paypilot.repository.AuditLogRepository;
import com.paypilot.repository.CustomerRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentOrderIntentRepository;
import com.paypilot.repository.PaymentRepository;
import com.paypilot.repository.UserRepository;
import com.paypilot.service.PayPalSandboxClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOrderIntentRepository paymentOrderIntentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AiExtractionLogRepository extractionLogRepository;

    @Autowired
    private PaymentLinkRepository paymentLinkRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private PayPalSandboxClient payPalSandboxClient;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        auditLogRepository.deleteAll();
        paymentRepository.deleteAll();
        paymentOrderIntentRepository.deleteAll();
        paymentLinkRepository.deleteAll();
        extractionLogRepository.deleteAll();
        invoiceRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void requiresJwtAuthenticationOnAllPaymentEndpoints() throws Exception {
        mockMvc.perform(post("/api/payments/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceId\":1}"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/payments/orders/ORD-UNAUTH-1/capture"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/payments/orders/ORD-UNAUTH-1/cancel"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/payments/invoice/1"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(payPalSandboxClient);
    }

    @Test
    void rejectsUnapprovedExpiredRevokedInvoicesAndOwnershipViolations() throws Exception {
        String ownerToken = registerAndGetToken("Owner Pay", "owner.pay@paypilot.test");
        String intruderToken = registerAndGetToken("Other User", "other.pay@paypilot.test");

        // 1. DRAFT invoice rejected before calling PayPal
        JsonNode draft = createDraftInvoice(ownerToken, new BigDecimal("500.00"), "USD", LocalDate.now().plusDays(10));
        long draftId = draft.get("id").asLong();

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", draftId))))
            .andExpect(status().isConflict());

        // 2. Approved then revoked invoice rejected
        approveInvoice(ownerToken, draftId, draft.get("snapshotHash").asText());
        mockMvc.perform(post("/api/invoices/" + draftId + "/revoke-approval")
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", draftId))))
            .andExpect(status().isConflict());

        // 3. Re-approved, then another user cannot initiate payment or read payment history
        JsonNode refreshedDraft = objectMapper.readTree(
            mockMvc.perform(get("/api/invoices/" + draftId)
                    .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
        );
        approveInvoice(ownerToken, draftId, refreshedDraft.get("snapshotHash").asText());

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + intruderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", draftId))))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/payments/invoice/" + draftId)
                .header("Authorization", "Bearer " + intruderToken))
            .andExpect(status().isForbidden());

        // 4. Expired approval timestamp rejected
        Invoice approved = invoiceRepository.findById(draftId).orElseThrow();
        approved.setApprovedAt(LocalDateTime.now().minusHours(200));
        invoiceRepository.saveAndFlush(approved);

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", draftId))))
            .andExpect(status().isConflict());

        verifyNoInteractions(payPalSandboxClient);

        List<AuditLog> failureLogs = auditLogRepository
            .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("INVOICE", draftId);
        assertThat(failureLogs).extracting(AuditLog::getAction).contains("PAYMENT_VERIFICATION_FAILED");
    }

    @Test
    void createsOrderReusesSameSnapshotCapturesCompletedAndEnforcesIdempotentCapture() throws Exception {
        String ownerToken = registerAndGetToken("Merchant A", "merchant.a@paypilot.test");
        String intruderToken = registerAndGetToken("Intruder A", "intruder.a@paypilot.test");

        JsonNode approved = createAndApproveInvoice(ownerToken, new BigDecimal("750.00"), "USD");
        long invoiceId = approved.get("id").asLong();
        String expectedHash = approved.get("snapshotHash").asText();

        when(payPalSandboxClient.createOrder(any(PayPalCreateOrderRequest.class)))
            .thenAnswer(inv -> {
                PayPalCreateOrderRequest req = inv.getArgument(0);
                assertThat(req.idempotencyKey()).startsWith("ord-" + invoiceId + "-");
                return new PayPalOrderResult(
                    "ORD-HAPPY-100",
                    "PAYER_ACTION_REQUIRED",
                    "https://www.sandbox.paypal.com/checkoutnow?token=ORD-HAPPY-100",
                    "payer-action",
                    req.idempotencyKey(),
                    req.amount(),
                    req.currency(),
                    Map.of("orderId", "ORD-HAPPY-100", "status", "PAYER_ACTION_REQUIRED", "idempotencyKey", req.idempotencyKey())
                );
            });

        // 1. Create PayPal order -> 201 Created with approval URL, no idempotencyKey leaked
        MvcResult firstOrderRes = mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", invoiceId))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paypalOrderId").value("ORD-HAPPY-100"))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.approvalUrl").value("https://www.sandbox.paypal.com/checkoutnow?token=ORD-HAPPY-100"))
            .andExpect(jsonPath("$.invoiceSnapshotHash").value(expectedHash))
            .andExpect(jsonPath("$.reused").value(false))
            .andExpect(jsonPath("$.summary.idempotencyKey").doesNotExist())
            .andReturn();
        assertThat(firstOrderRes.getResponse().getContentAsString()).doesNotContain("test-sandbox-client-secret");

        // 2. Repeated order request for same snapshot reuses existing valid order without duplicate PayPal call
        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", invoiceId))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paypalOrderId").value("ORD-HAPPY-100"))
            .andExpect(jsonPath("$.reused").value(true));

        verify(payPalSandboxClient, times(1)).createOrder(any());
        assertThat(paymentRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId)).hasSize(1);

        // 3. Another user cannot capture or cancel the order
        mockMvc.perform(post("/api/payments/orders/ORD-HAPPY-100/capture")
                .header("Authorization", "Bearer " + intruderToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/payments/orders/ORD-HAPPY-100/cancel")
                .header("Authorization", "Bearer " + intruderToken))
            .andExpect(status().isForbidden());

        // 4. Verified COMPLETED capture marks Payment CAPTURED and Invoice PAID
        when(payPalSandboxClient.captureOrder(eq("ORD-HAPPY-100"), anyString()))
            .thenReturn(new PayPalCaptureResult(
                "ORD-HAPPY-100",
                "COMPLETED",
                "CAP-HAPPY-100",
                "COMPLETED",
                true,
                false,
                null,
                new BigDecimal("750.00"),
                "USD",
                "2026-11-11T12:00:00Z",
                Map.of("orderId", "ORD-HAPPY-100", "captureId", "CAP-HAPPY-100", "captureStatus", "COMPLETED")
            ));

        mockMvc.perform(post("/api/payments/orders/ORD-HAPPY-100/capture")
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CAPTURED"))
            .andExpect(jsonPath("$.invoiceStatus").value("PAID"))
            .andExpect(jsonPath("$.paypalCaptureId").value("CAP-HAPPY-100"))
            .andExpect(jsonPath("$.completed").value(true))
            .andExpect(jsonPath("$.capturedAt").isNotEmpty());

        Invoice paidInvoice = invoiceRepository.findById(invoiceId).orElseThrow();
        assertThat(paidInvoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(paidInvoice.getPaidAt()).isNotNull();

        // 5. Duplicate capture is idempotent and does not call PayPal again
        mockMvc.perform(post("/api/payments/orders/ORD-HAPPY-100/capture")
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CAPTURED"))
            .andExpect(jsonPath("$.reused").value(true));

        verify(payPalSandboxClient, times(1)).captureOrder(eq("ORD-HAPPY-100"), anyString());

        // 6. Payment history endpoint returns safe payment record
        mockMvc.perform(get("/api/payments/invoice/" + invoiceId)
                .header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].paypalOrderId").value("ORD-HAPPY-100"))
            .andExpect(jsonPath("$[0].status").value("CAPTURED"));
    }

    @Test
    void handlesPendingDeclinedMismatchedAndUnavailableCapturesWithoutMarkingInvoicePaid() throws Exception {
        String token = registerAndGetToken("Edge Case Merchant", "edges@paypilot.test");

        // --- A. PENDING capture -> safe GET order details check -> later COMPLETED ---
        JsonNode pendingInv = createAndApproveInvoice(token, new BigDecimal("300.00"), "USD");
        long pendingInvId = pendingInv.get("id").asLong();
        mockOrderCreation("ORD-PEND-1", new BigDecimal("300.00"), "USD");

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", pendingInvId))))
            .andExpect(status().isCreated());

        when(payPalSandboxClient.captureOrder(eq("ORD-PEND-1"), anyString()))
            .thenReturn(new PayPalCaptureResult(
                "ORD-PEND-1", "COMPLETED", "CAP-PEND-1", "PENDING",
                false, true, "PENDING_REVIEW",
                new BigDecimal("300.00"), "USD", null,
                Map.of("captureId", "CAP-PEND-1", "captureStatus", "PENDING", "pendingReason", "PENDING_REVIEW")
            ));

        mockMvc.perform(post("/api/payments/orders/ORD-PEND-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.invoiceStatus").value("APPROVED"))
            .andExpect(jsonPath("$.pending").value(true))
            .andExpect(jsonPath("$.completed").value(false))
            .andExpect(jsonPath("$.pendingReason").value("PENDING_REVIEW"));

        assertThat(invoiceRepository.findById(pendingInvId).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.APPROVED);

        // Retrying capture on PENDING payment calls GET order details, NOT captureOrder again
        when(payPalSandboxClient.getOrderDetails("ORD-PEND-1"))
            .thenReturn(new PayPalOrderDetailsResult(
                "ORD-PEND-1", "COMPLETED", new BigDecimal("300.00"), "USD",
                List.of(new PayPalCaptureResult(
                    "ORD-PEND-1", "COMPLETED", "CAP-PEND-1", "PENDING",
                    false, true, "ECHECK",
                    new BigDecimal("300.00"), "USD", null,
                    Map.of("captureId", "CAP-PEND-1", "captureStatus", "PENDING", "pendingReason", "ECHECK")
                ))
            ))
            .thenReturn(new PayPalOrderDetailsResult(
                "ORD-PEND-1", "COMPLETED", new BigDecimal("300.00"), "USD",
                List.of(new PayPalCaptureResult(
                    "ORD-PEND-1", "COMPLETED", "CAP-PEND-1", "COMPLETED",
                    true, false, null,
                    new BigDecimal("300.00"), "USD", "2026-11-11T12:15:00Z",
                    Map.of("captureId", "CAP-PEND-1", "captureStatus", "COMPLETED")
                ))
            ));

        mockMvc.perform(post("/api/payments/orders/ORD-PEND-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.pending").value(true))
            .andExpect(jsonPath("$.pendingReason").value("ECHECK"))
            .andExpect(jsonPath("$.invoiceStatus").value("APPROVED"));

        mockMvc.perform(post("/api/payments/orders/ORD-PEND-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CAPTURED"))
            .andExpect(jsonPath("$.invoiceStatus").value("PAID"));

        verify(payPalSandboxClient, times(1)).captureOrder(eq("ORD-PEND-1"), anyString());
        verify(payPalSandboxClient, times(2)).getOrderDetails("ORD-PEND-1");

        // --- B. DECLINED capture never marks Invoice PAID ---
        JsonNode declinedInv = createAndApproveInvoice(token, new BigDecimal("200.00"), "USD");
        long declinedInvId = declinedInv.get("id").asLong();
        mockOrderCreation("ORD-DECL-1", new BigDecimal("200.00"), "USD");

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", declinedInvId))))
            .andExpect(status().isCreated());

        when(payPalSandboxClient.captureOrder(eq("ORD-DECL-1"), anyString()))
            .thenReturn(new PayPalCaptureResult(
                "ORD-DECL-1", "COMPLETED", "CAP-DECL-1", "DECLINED",
                false, false, "CVV2_FAILURE",
                new BigDecimal("200.00"), "USD", null,
                Map.of("captureId", "CAP-DECL-1", "captureStatus", "DECLINED", "pendingReason", "CVV2_FAILURE")
            ));

        mockMvc.perform(post("/api/payments/orders/ORD-DECL-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity());

        assertThat(paymentRepository.findByPaypalOrderId("ORD-DECL-1").orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.FAILED);
        assertThat(invoiceRepository.findById(declinedInvId).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.APPROVED);

        // --- C. Amount/currency mismatch capture fails verification ---
        JsonNode mismatchInv = createAndApproveInvoice(token, new BigDecimal("400.00"), "USD");
        long mismatchInvId = mismatchInv.get("id").asLong();
        mockOrderCreation("ORD-MISMATCH-1", new BigDecimal("400.00"), "USD");

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", mismatchInvId))))
            .andExpect(status().isCreated());

        when(payPalSandboxClient.captureOrder(eq("ORD-MISMATCH-1"), anyString()))
            .thenReturn(new PayPalCaptureResult(
                "ORD-MISMATCH-1", "COMPLETED", "CAP-MISMATCH-1", "COMPLETED",
                true, false, null,
                new BigDecimal("10.00"), "USD", "2026-11-11T12:20:00Z",
                Map.of("captureId", "CAP-MISMATCH-1", "amount", "10.00")
            ));

        mockMvc.perform(post("/api/payments/orders/ORD-MISMATCH-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity());

        assertThat(paymentRepository.findByPaypalOrderId("ORD-MISMATCH-1").orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.FAILED);
        assertThat(invoiceRepository.findById(mismatchInvId).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.APPROVED);

        // --- D. Network failure leaves Invoice APPROVED and returns 503 ---
        JsonNode downInv = createAndApproveInvoice(token, new BigDecimal("125.00"), "USD");
        long downInvId = downInv.get("id").asLong();
        mockOrderCreation("ORD-DOWN-1", new BigDecimal("125.00"), "USD");

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", downInvId))))
            .andExpect(status().isCreated());

        when(payPalSandboxClient.captureOrder(eq("ORD-DOWN-1"), anyString()))
            .thenThrow(new PayPalUnavailableException("PayPal Sandbox timed out."));

        mockMvc.perform(post("/api/payments/orders/ORD-DOWN-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isServiceUnavailable());

        assertThat(invoiceRepository.findById(downInvId).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.APPROVED);

        // --- E. Explicit FAILED capture, currency mismatch, and malformed response ---
        JsonNode failInv = createAndApproveInvoice(token, new BigDecimal("250.00"), "USD");
        long failInvId = failInv.get("id").asLong();
        mockOrderCreation("ORD-FAIL-1", new BigDecimal("250.00"), "USD");

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", failInvId))))
            .andExpect(status().isCreated());

        when(payPalSandboxClient.captureOrder(eq("ORD-FAIL-1"), anyString()))
            .thenReturn(new PayPalCaptureResult(
                "ORD-FAIL-1", "COMPLETED", "CAP-FAIL-1", "FAILED",
                false, false, "INTERNAL_FAILURE",
                new BigDecimal("250.00"), "USD", null,
                Map.of("captureId", "CAP-FAIL-1", "captureStatus", "FAILED")
            ));

        mockMvc.perform(post("/api/payments/orders/ORD-FAIL-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity());
        assertThat(paymentRepository.findByPaypalOrderId("ORD-FAIL-1").orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.FAILED);
        assertThat(invoiceRepository.findById(failInvId).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.APPROVED);

        JsonNode currInv = createAndApproveInvoice(token, new BigDecimal("180.00"), "USD");
        long currInvId = currInv.get("id").asLong();
        mockOrderCreation("ORD-CURR-1", new BigDecimal("180.00"), "USD");

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", currInvId))))
            .andExpect(status().isCreated());

        when(payPalSandboxClient.captureOrder(eq("ORD-CURR-1"), anyString()))
            .thenReturn(new PayPalCaptureResult(
                "ORD-CURR-1", "COMPLETED", "CAP-CURR-1", "COMPLETED",
                true, false, null,
                new BigDecimal("180.00"), "EUR", "2026-11-11T12:25:00Z",
                Map.of("captureId", "CAP-CURR-1", "currency", "EUR")
            ));

        mockMvc.perform(post("/api/payments/orders/ORD-CURR-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity());
        assertThat(paymentRepository.findByPaypalOrderId("ORD-CURR-1").orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.FAILED);
        assertThat(invoiceRepository.findById(currInvId).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.APPROVED);

        JsonNode malInv = createAndApproveInvoice(token, new BigDecimal("95.00"), "USD");
        long malInvId = malInv.get("id").asLong();
        mockOrderCreation("ORD-MAL-1", new BigDecimal("95.00"), "USD");

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", malInvId))))
            .andExpect(status().isCreated());

        when(payPalSandboxClient.captureOrder(eq("ORD-MAL-1"), anyString()))
            .thenThrow(new PayPalMalformedResponseException("PayPal response was malformed."));

        mockMvc.perform(post("/api/payments/orders/ORD-MAL-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadGateway());
        assertThat(invoiceRepository.findById(malInvId).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.APPROVED);
    }

    @Test
    void blocksSnapshotChangesAndLocalCancellationAndAllowsFreshOrderAfterLocalCancel() throws Exception {
        String token = registerAndGetToken("Snapshot Guard", "snap.guard@paypilot.test");

        // 1. Local cancel prevents capture without claiming remote PayPal cancellation
        JsonNode approved1 = createAndApproveInvoice(token, new BigDecimal("600.00"), "USD");
        long inv1Id = approved1.get("id").asLong();
        mockOrderCreation("ORD-CANCEL-1", new BigDecimal("600.00"), "USD");

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", inv1Id))))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/payments/orders/ORD-CANCEL-1/cancel")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Buyer exited checkout\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.locallyCancelled").value(true))
            .andExpect(jsonPath("$.paypalCancellationConfirmed").value(false));

        // Capture of locally cancelled order blocked with zero PayPal capture calls
        mockMvc.perform(post("/api/payments/orders/ORD-CANCEL-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());
        verify(payPalSandboxClient, never()).captureOrder(eq("ORD-CANCEL-1"), anyString());

        // Creating a fresh order on still-APPROVED invoice generates a new attempt key (-a1)
        mockOrderCreation("ORD-AFTER-CANCEL-2", new BigDecimal("600.00"), "USD");
        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", inv1Id))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paypalOrderId").value("ORD-AFTER-CANCEL-2"));

        assertThat(paymentRepository.findByPaypalOrderId("ORD-AFTER-CANCEL-2").orElseThrow().getIdempotencyKey())
            .endsWith("-a1");

        // 2. Editing the invoice invalidates approval & locally cancels uncaptured order -> capture blocked
        mockMvc.perform(put("/api/invoices/" + inv1Id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", "Client Updated",
                    "clientEmail", "client@example.com",
                    "description", "Updated scope",
                    "amount", new BigDecimal("650.00"),
                    "currency", "USD",
                    "dueDate", LocalDate.now().plusDays(14).toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(post("/api/payments/orders/ORD-AFTER-CANCEL-2/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());
        verify(payPalSandboxClient, never()).captureOrder(eq("ORD-AFTER-CANCEL-2"), anyString());

        // 3. Direct DB/snapshot drift on an APPROVED invoice blocks order creation & cancels stale active order
        JsonNode approved2 = createAndApproveInvoice(token, new BigDecimal("850.00"), "USD");
        long inv2Id = approved2.get("id").asLong();
        mockOrderCreation("ORD-DRIFT-1", new BigDecimal("850.00"), "USD");

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", inv2Id))))
            .andExpect(status().isCreated());

        Invoice tampered = invoiceRepository.findById(inv2Id).orElseThrow();
        tampered.setAmount(new BigDecimal("999.00"));
        invoiceRepository.saveAndFlush(tampered);

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", inv2Id))))
            .andExpect(status().isConflict());

        mockMvc.perform(post("/api/payments/orders/ORD-DRIFT-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());
        assertThat(paymentRepository.findByPaypalOrderId("ORD-DRIFT-1").orElseThrow().getStatus())
            .isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void preventsDuplicateOrdersAndDuplicateCapturesUnderConcurrentLoad() throws Exception {
        String token = registerAndGetToken("Concurrent Merchant", "concurrent@paypilot.test");
        JsonNode approved = createAndApproveInvoice(token, new BigDecimal("550.00"), "USD");
        long invoiceId = approved.get("id").asLong();

        AtomicInteger createCalls = new AtomicInteger(0);
        doAnswer(inv -> {
            Thread.sleep(30L);
            createCalls.incrementAndGet();
            PayPalCreateOrderRequest req = inv.getArgument(0);
            return new PayPalOrderResult(
                "ORD-CONC-1",
                "PAYER_ACTION_REQUIRED",
                "https://www.sandbox.paypal.com/checkoutnow?token=ORD-CONC-1",
                "payer-action",
                req.idempotencyKey(),
                req.amount(),
                req.currency(),
                Map.of("orderId", "ORD-CONC-1", "status", "PAYER_ACTION_REQUIRED")
            );
        }).when(payPalSandboxClient).createOrder(any(PayPalCreateOrderRequest.class));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch orderStartLatch = new CountDownLatch(1);
            List<Future<Integer>> orderFutures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                orderFutures.add(pool.submit(() -> {
                    orderStartLatch.await();
                    return mockMvc.perform(post("/api/payments/orders")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("invoiceId", invoiceId))))
                        .andReturn().getResponse().getStatus();
                }));
            }
            orderStartLatch.countDown();

            for (Future<Integer> future : orderFutures) {
                int code = future.get(15, TimeUnit.SECONDS);
                assertThat(code).isBetween(200, 201);
            }
            assertThat(createCalls.get()).isEqualTo(1);
            assertThat(paymentRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId)).hasSize(1);

            AtomicInteger captureCalls = new AtomicInteger(0);
            doAnswer(inv -> {
                Thread.sleep(30L);
                captureCalls.incrementAndGet();
                return new PayPalCaptureResult(
                    "ORD-CONC-1", "COMPLETED", "CAP-CONC-1", "COMPLETED",
                    true, false, null,
                    new BigDecimal("550.00"), "USD", "2026-11-11T12:30:00Z",
                    Map.of("orderId", "ORD-CONC-1", "captureId", "CAP-CONC-1", "captureStatus", "COMPLETED")
                );
            }).when(payPalSandboxClient).captureOrder(eq("ORD-CONC-1"), anyString());

            CountDownLatch captureStartLatch = new CountDownLatch(1);
            List<Future<Integer>> captureFutures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                captureFutures.add(pool.submit(() -> {
                    captureStartLatch.await();
                    return mockMvc.perform(post("/api/payments/orders/ORD-CONC-1/capture")
                            .header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getStatus();
                }));
            }
            captureStartLatch.countDown();

            for (Future<Integer> future : captureFutures) {
                assertThat(future.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            }
            assertThat(captureCalls.get()).isEqualTo(1);
            assertThat(paymentRepository.findByPaypalOrderId("ORD-CONC-1").orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CAPTURED);
            assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.PAID);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void recoversLostOrderAndCaptureResponsesAndHandlesOrderAlreadyCapturedAndPendingToFailed() throws Exception {
        String token = registerAndGetToken("Recovery Merchant", "recovery@paypilot.test");

        // --- A. PayPal creates order but HTTP response is lost -> retry reuses exact intent key ---
        JsonNode inv1 = createAndApproveInvoice(token, new BigDecimal("420.00"), "USD");
        long inv1Id = inv1.get("id").asLong();
        List<String> observedCreateKeys = new ArrayList<>();

        when(payPalSandboxClient.createOrder(any(PayPalCreateOrderRequest.class)))
            .thenAnswer(inv -> {
                PayPalCreateOrderRequest req = inv.getArgument(0);
                observedCreateKeys.add(req.idempotencyKey());
                throw new PayPalUnavailableException("Lost HTTP response after PayPal created order.");
            })
            .thenAnswer(inv -> {
                PayPalCreateOrderRequest req = inv.getArgument(0);
                observedCreateKeys.add(req.idempotencyKey());
                return new PayPalOrderResult(
                    "ORD-LOST-REC-1",
                    "PAYER_ACTION_REQUIRED",
                    "https://www.sandbox.paypal.com/checkoutnow?token=ORD-LOST-REC-1",
                    "payer-action",
                    req.idempotencyKey(),
                    req.amount(),
                    req.currency(),
                    Map.of("orderId", "ORD-LOST-REC-1", "status", "PAYER_ACTION_REQUIRED")
                );
            });

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", inv1Id))))
            .andExpect(status().isServiceUnavailable());

        PaymentOrderIntent pendingIntent = paymentOrderIntentRepository
            .findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(inv1Id, PaymentOrderIntentStatus.PENDING)
            .orElseThrow();
        assertThat(pendingIntent.getIdempotencyKey()).isEqualTo(observedCreateKeys.get(0));
        assertThat(paymentRepository.findByInvoiceIdOrderByCreatedAtDesc(inv1Id)).isEmpty();

        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", inv1Id))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paypalOrderId").value("ORD-LOST-REC-1"));

        assertThat(observedCreateKeys).hasSize(2);
        assertThat(observedCreateKeys.get(1)).isEqualTo(observedCreateKeys.get(0));
        assertThat(paymentOrderIntentRepository.findByInvoiceIdOrderByCreatedAtDesc(inv1Id))
            .singleElement()
            .satisfies(i -> {
                assertThat(i.getStatus()).isEqualTo(PaymentOrderIntentStatus.COMPLETED);
                assertThat(i.getActiveInvoiceId()).isNull();
                assertThat(i.getPaypalOrderId()).isEqualTo("ORD-LOST-REC-1");
            });

        // --- B. Capture response lost (503) -> cancel/edit/revoke blocked -> retry reconciles via GET ---
        when(payPalSandboxClient.captureOrder(eq("ORD-LOST-REC-1"), anyString()))
            .thenThrow(new PayPalUnavailableException("Timeout reading PayPal capture response."));

        mockMvc.perform(post("/api/payments/orders/ORD-LOST-REC-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isServiceUnavailable());

        Payment postTimeoutPayment = paymentRepository.findByPaypalOrderId("ORD-LOST-REC-1").orElseThrow();
        assertThat(postTimeoutPayment.getCaptureRequestedAt()).isNotNull();
        assertThat(postTimeoutPayment.getCaptureIdempotencyKey()).isNotBlank();

        // Ambiguous capture blocks cancel, edit, and revoke
        mockMvc.perform(post("/api/payments/orders/ORD-LOST-REC-1/cancel")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/invoices/" + inv1Id + "/revoke-approval")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());
        mockMvc.perform(put("/api/invoices/" + inv1Id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", "Acme",
                    "clientEmail", "billing@acme.test",
                    "description", "Changed during ambiguous capture",
                    "amount", new BigDecimal("500.00"),
                    "currency", "USD",
                    "dueDate", LocalDate.now().plusDays(14).toString()
                ))))
            .andExpect(status().isConflict());

        when(payPalSandboxClient.getOrderDetails("ORD-LOST-REC-1"))
            .thenReturn(new PayPalOrderDetailsResult(
                "ORD-LOST-REC-1", "COMPLETED", new BigDecimal("420.00"), "USD",
                List.of(new PayPalCaptureResult(
                    "ORD-LOST-REC-1", "COMPLETED", "CAP-LOST-REC-1", "COMPLETED",
                    true, false, null,
                    new BigDecimal("420.00"), "USD", "2026-11-11T12:40:00Z",
                    Map.of("captureId", "CAP-LOST-REC-1", "captureStatus", "COMPLETED")
                ))
            ));

        mockMvc.perform(post("/api/payments/orders/ORD-LOST-REC-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CAPTURED"))
            .andExpect(jsonPath("$.invoiceStatus").value("PAID"));

        verify(payPalSandboxClient, times(1)).captureOrder(eq("ORD-LOST-REC-1"), anyString());
        verify(payPalSandboxClient, times(1)).getOrderDetails("ORD-LOST-REC-1");

        // --- C. ORDER_ALREADY_CAPTURED 422 recovers via GET order details ---
        JsonNode inv2 = createAndApproveInvoice(token, new BigDecimal("150.00"), "USD");
        long inv2Id = inv2.get("id").asLong();
        mockOrderCreation("ORD-ALREADY-1", new BigDecimal("150.00"), "USD");
        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", inv2Id))))
            .andExpect(status().isCreated());

        when(payPalSandboxClient.captureOrder(eq("ORD-ALREADY-1"), anyString()))
            .thenThrow(new PayPalApiException(
                422, "UNPROCESSABLE_ENTITY", "ORDER_ALREADY_CAPTURED", "dbg-oac", "Order already captured."
            ));
        when(payPalSandboxClient.getOrderDetails("ORD-ALREADY-1"))
            .thenReturn(new PayPalOrderDetailsResult(
                "ORD-ALREADY-1", "COMPLETED", new BigDecimal("150.00"), "USD",
                List.of(new PayPalCaptureResult(
                    "ORD-ALREADY-1", "COMPLETED", "CAP-ALREADY-1", "COMPLETED",
                    true, false, null,
                    new BigDecimal("150.00"), "USD", "2026-11-11T12:45:00Z",
                    Map.of("captureId", "CAP-ALREADY-1", "captureStatus", "COMPLETED")
                ))
            ));

        mockMvc.perform(post("/api/payments/orders/ORD-ALREADY-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CAPTURED"))
            .andExpect(jsonPath("$.invoiceStatus").value("PAID"))
            .andExpect(jsonPath("$.paypalCaptureId").value("CAP-ALREADY-1"));

        // --- D. PENDING capture later resolving to DECLINED releases active slot ---
        JsonNode inv3 = createAndApproveInvoice(token, new BigDecimal("310.00"), "USD");
        long inv3Id = inv3.get("id").asLong();
        mockOrderCreation("ORD-PEND-FAIL-1", new BigDecimal("310.00"), "USD");
        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", inv3Id))))
            .andExpect(status().isCreated());

        when(payPalSandboxClient.captureOrder(eq("ORD-PEND-FAIL-1"), anyString()))
            .thenReturn(new PayPalCaptureResult(
                "ORD-PEND-FAIL-1", "COMPLETED", "CAP-PF-1", "PENDING",
                false, true, "ECHECK",
                new BigDecimal("310.00"), "USD", null,
                Map.of("captureId", "CAP-PF-1", "captureStatus", "PENDING", "pendingReason", "ECHECK")
            ));
        mockMvc.perform(post("/api/payments/orders/ORD-PEND-FAIL-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isAccepted());

        when(payPalSandboxClient.getOrderDetails("ORD-PEND-FAIL-1"))
            .thenReturn(new PayPalOrderDetailsResult(
                "ORD-PEND-FAIL-1", "COMPLETED", new BigDecimal("310.00"), "USD",
                List.of(new PayPalCaptureResult(
                    "ORD-PEND-FAIL-1", "COMPLETED", "CAP-PF-1", "DECLINED",
                    false, false, "ECHECK_BOUNCED",
                    new BigDecimal("310.00"), "USD", null,
                    Map.of("captureId", "CAP-PF-1", "captureStatus", "DECLINED", "pendingReason", "ECHECK_BOUNCED")
                ))
            ));
        mockMvc.perform(post("/api/payments/orders/ORD-PEND-FAIL-1/capture")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity());

        Payment failedPayment = paymentRepository.findByPaypalOrderId("ORD-PEND-FAIL-1").orElseThrow();
        assertThat(failedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failedPayment.getActiveInvoiceId()).isNull();
        assertThat(invoiceRepository.findById(inv3Id).orElseThrow().getStatus())
            .isEqualTo(InvoiceStatus.APPROVED);
    }

    private void mockOrderCreation(String orderId, BigDecimal amount, String currency) {
        when(payPalSandboxClient.createOrder(any(PayPalCreateOrderRequest.class)))
            .thenAnswer(inv -> {
                PayPalCreateOrderRequest req = inv.getArgument(0);
                return new PayPalOrderResult(
                    orderId,
                    "PAYER_ACTION_REQUIRED",
                    "https://www.sandbox.paypal.com/checkoutnow?token=" + orderId,
                    "payer-action",
                    req.idempotencyKey(),
                    amount,
                    currency,
                    Map.of("orderId", orderId, "status", "PAYER_ACTION_REQUIRED")
                );
            });
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
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode createAndApproveInvoice(String token, BigDecimal amount, String currency) throws Exception {
        JsonNode draft = createDraftInvoice(token, amount, currency, LocalDate.now().plusDays(14));
        return approveInvoice(token, draft.get("id").asLong(), draft.get("snapshotHash").asText());
    }

    private JsonNode createDraftInvoice(
            String token,
            BigDecimal amount,
            String currency,
            LocalDate dueDate) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/invoices")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", "Acme Client",
                    "clientEmail", "billing@acme.test",
                    "description", "Cloud consulting engagement",
                    "amount", amount,
                    "currency", currency,
                    "dueDate", dueDate.toString()
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode approveInvoice(String token, long invoiceId, String snapshotHash) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", snapshotHash,
                    "confirmApproval", true
                ))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }
}
