package com.paypilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.dto.paymentlink.GeneratedPaymentLinkResponse;
import com.paypilot.dto.paypal.PayPalCaptureResult;
import com.paypilot.dto.paypal.PayPalCreateOrderRequest;
import com.paypilot.dto.paypal.PayPalOrderDetailsResult;
import com.paypilot.dto.paypal.PayPalOrderResult;
import com.paypilot.model.AuditLog;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentLink;
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
import com.paypilot.service.PaymentLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
class PublicPaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentLinkService paymentLinkService;

    @Autowired
    private PaymentLinkRepository paymentLinkRepository;

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
    void enforcesPublicRouteIsolationAndRejectsUnknownOrMalformedTokensWithoutLeakingData() throws Exception {
        String fakeToken = "FakePublicTokenThatDoesNotExistAnywhere12345";

        MvcResult notFoundResult = mockMvc.perform(get("/api/public/pay/" + fakeToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.path").value("/api/public/pay/[REDACTED]"))
            .andReturn();
        assertThat(notFoundResult.getResponse().getContentAsString()).doesNotContain(fakeToken);

        mockMvc.perform(get("/api/public/pay/bad!token"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.path").value("/api/public/pay/[REDACTED]"));

        // Private seller routes remain strictly locked down without JWT
        mockMvc.perform(get("/api/invoices")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/payments/invoice/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/payments/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceId\":1}"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(payPalSandboxClient);
    }

    @Test
    void completesEndToEndCustomerPaymentWithoutJwtEnforcesIdempotencyAndHidesPrivateFields() throws Exception {
        String sellerJwt = registerAndGetToken("Apex Creative", "apex@seller.test");
        JsonNode invoiceJson = createAndApproveInvoice(sellerJwt, "Alex Buyer", "alex@private-buyer.test", "750.00", "USD");
        long invoiceId = invoiceJson.get("id").asLong();
        String invoiceNumber = invoiceJson.get("invoiceNumber").asText();

        Long sellerId = invoiceRepository.findById(invoiceId).orElseThrow().getUser().getId();
        GeneratedPaymentLinkResponse generated = paymentLinkService.generateOrRotateLink(sellerId, invoiceId, 14, "127.0.0.1");
        String rawToken = generated.rawToken();

        // 1. Public view works without JWT and excludes private/internal fields
        MvcResult publicViewRes = mockMvc.perform(get("/api/public/pay/" + rawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("ACTIVE"))
            .andExpect(jsonPath("$.payable").value(true))
            .andExpect(jsonPath("$.sandboxMode").value(true))
            .andExpect(jsonPath("$.invoiceNumber").value(invoiceNumber))
            .andExpect(jsonPath("$.sellerDisplayName").value("Apex Creative"))
            .andExpect(jsonPath("$.customerName").value("Alex Buyer"))
            .andExpect(jsonPath("$.amount").value(750.00))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andReturn();

        String body = publicViewRes.getResponse().getContentAsString();
        assertThat(body)
            .doesNotContain("alex@private-buyer.test")
            .doesNotContain("apex@seller.test")
            .doesNotContain(invoiceJson.get("approvedSnapshotHash").asText())
            .doesNotContain(rawToken);

        PaymentLink updatedLink = paymentLinkRepository.findByTokenHash(paymentLinkService.hashRawTokenOrThrow(rawToken)).orElseThrow();
        assertThat(updatedLink.getAccessCount()).isEqualTo(1);

        // Non-tracked status refresh (?trackView=false) must NOT increment view count
        mockMvc.perform(get("/api/public/pay/" + rawToken + "?trackView=false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("ACTIVE"));

        PaymentLink afterRefresh = paymentLinkRepository.findByTokenHash(paymentLinkService.hashRawTokenOrThrow(rawToken)).orElseThrow();
        assertThat(afterRefresh.getAccessCount()).isEqualTo(1);

        // A second genuine customer visit increments accessCount cleanly to 2
        mockMvc.perform(get("/api/public/pay/" + rawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("ACTIVE"));

        PaymentLink afterSecondVisit = paymentLinkRepository.findByTokenHash(paymentLinkService.hashRawTokenOrThrow(rawToken)).orElseThrow();
        assertThat(afterSecondVisit.getAccessCount()).isEqualTo(2);

        // 2. Rejects open-redirect attempts
        mockMvc.perform(post("/api/public/pay/" + rawToken + "/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "returnUrl", "https://evil.example/pay/" + rawToken,
                    "cancelUrl", "http://localhost:5173/pay/" + rawToken + "?paypal=cancel"
                ))))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(payPalSandboxClient);

        // 3. Creates PayPal Sandbox order bound to customer payment link
        when(payPalSandboxClient.createOrder(any())).thenReturn(
            mockOrder("ORD-CUST-750", "750.00", "USD")
        );

        mockMvc.perform(post("/api/public/pay/" + rawToken + "/orders"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paypalOrderId").value("ORD-CUST-750"))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.approvalUrl").value("https://www.sandbox.paypal.com/checkoutnow?token=ORD-CUST-750"))
            .andExpect(jsonPath("$.reused").value(false))
            .andExpect(jsonPath("$.sandboxMode").value(true));

        ArgumentCaptor<PayPalCreateOrderRequest> reqCaptor = ArgumentCaptor.forClass(PayPalCreateOrderRequest.class);
        verify(payPalSandboxClient, times(1)).createOrder(reqCaptor.capture());
        assertThat(reqCaptor.getValue().returnUrl()).isEqualTo("http://localhost:5173/pay/" + rawToken + "?paypal=return");
        assertThat(reqCaptor.getValue().cancelUrl()).isEqualTo("http://localhost:5173/pay/" + rawToken + "?paypal=cancel");

        Payment savedPayment = paymentRepository.findByPaypalOrderId("ORD-CUST-750").orElseThrow();
        assertThat(savedPayment.getPaymentLink()).isNotNull();
        assertThat(savedPayment.getPaymentLink().getId()).isEqualTo(generated.link().id());

        // Reusing uncaptured order returns 200 without calling PayPal again
        mockMvc.perform(post("/api/public/pay/" + rawToken + "/orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paypalOrderId").value("ORD-CUST-750"))
            .andExpect(jsonPath("$.reused").value(true));
        verify(payPalSandboxClient, times(1)).createOrder(any());

        // Invoice is still APPROVED until verified capture
        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.APPROVED);

        // 4. Verified capture transitions invoice to PAID and returns receipt
        when(payPalSandboxClient.captureOrder(eq("ORD-CUST-750"), any())).thenReturn(
            mockCapture("ORD-CUST-750", "CAP-CUST-750", "COMPLETED", true, false, null, "750.00", "USD")
        );

        mockMvc.perform(post("/api/public/pay/" + rawToken + "/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orderId", "ORD-CUST-750"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("PAID"))
            .andExpect(jsonPath("$.payable").value(false))
            .andExpect(jsonPath("$.invoiceStatus").value("PAID"))
            .andExpect(jsonPath("$.receipt.invoiceNumber").value(invoiceNumber))
            .andExpect(jsonPath("$.receipt.paypalOrderId").value("ORD-CUST-750"))
            .andExpect(jsonPath("$.receipt.paypalCaptureId").value("CAP-CUST-750"))
            .andExpect(jsonPath("$.receipt.amount").value(750.00))
            .andExpect(jsonPath("$.receipt.sandboxMode").value(true));

        // 5. Idempotent capture replay never calls PayPal capture twice
        mockMvc.perform(post("/api/public/pay/" + rawToken + "/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orderId", "ORD-CUST-750"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("PAID"))
            .andExpect(jsonPath("$.receipt.paypalCaptureId").value("CAP-CUST-750"));
        verify(payPalSandboxClient, times(1)).captureOrder(eq("ORD-CUST-750"), any());

        // Paid invoice cannot create duplicate orders
        mockMvc.perform(post("/api/public/pay/" + rawToken + "/orders"))
            .andExpect(status().isConflict());

        // Audit logs never contain raw payment-link token
        List<AuditLog> audits = auditLogRepository.findAll();
        assertThat(audits).isNotEmpty();
        assertThat(audits).allSatisfy(log -> assertThat(String.valueOf(log.getDetails())).doesNotContain(rawToken));
    }

    @Test
    void handlesPendingDeclinedMismatchedCapturesAndCancelRetrySafely() throws Exception {
        String sellerJwt = registerAndGetToken("Beta Services", "beta@seller.test");
        JsonNode invoiceJson = createAndApproveInvoice(sellerJwt, "Jordan", "jordan@buyer.test", "300.00", "USD");
        long invoiceId = invoiceJson.get("id").asLong();
        Long sellerId = invoiceRepository.findById(invoiceId).orElseThrow().getUser().getId();
        String rawToken = paymentLinkService.generateOrRotateLink(sellerId, invoiceId, 14, "127.0.0.1").rawToken();

        when(payPalSandboxClient.createOrder(any())).thenReturn(
            mockOrder("ORD-CANCEL-1", "300.00", "USD"),
            mockOrder("ORD-RETRY-2", "300.00", "USD")
        );

        // Create and cancel order cleanly
        mockMvc.perform(post("/api/public/pay/" + rawToken + "/orders")).andExpect(status().isCreated());
        mockMvc.perform(post("/api/public/pay/" + rawToken + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orderId", "ORD-CANCEL-1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("ACTIVE"))
            .andExpect(jsonPath("$.payable").value(true))
            .andExpect(jsonPath("$.activePayment").isEmpty());

        assertThat(paymentRepository.findByPaypalOrderId("ORD-CANCEL-1").orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELLED);

        // Retry creates fresh order ORD-RETRY-2
        mockMvc.perform(post("/api/public/pay/" + rawToken + "/orders"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paypalOrderId").value("ORD-RETRY-2"));

        // Pending capture leaves invoice APPROVED (202 Accepted), NOT PAID
        when(payPalSandboxClient.captureOrder(eq("ORD-RETRY-2"), any())).thenReturn(
            mockCapture("ORD-RETRY-2", "CAP-PEND-2", "PENDING", false, true, "PENDING_REVIEW", "300.00", "USD")
        );

        mockMvc.perform(post("/api/public/pay/" + rawToken + "/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orderId", "ORD-RETRY-2"))))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.linkState").value("ACTIVE"))
            .andExpect(jsonPath("$.payable").value(false))
            .andExpect(jsonPath("$.invoiceStatus").value("APPROVED"))
            .andExpect(jsonPath("$.activePayment.status").value("PENDING"))
            .andExpect(jsonPath("$.activePayment.pendingReason").value("PENDING_REVIEW"));

        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.APPROVED);

        // Follow-up recovery rejects amount mismatch (422) without marking PAID
        when(payPalSandboxClient.getOrderDetails("ORD-RETRY-2")).thenReturn(new PayPalOrderDetailsResult(
            "ORD-RETRY-2",
            "COMPLETED",
            new BigDecimal("199.00"),
            "USD",
            List.of(mockCapture("ORD-RETRY-2", "CAP-PEND-2", "COMPLETED", true, false, null, "199.00", "USD"))
        ));

        mockMvc.perform(post("/api/public/pay/" + rawToken + "/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orderId", "ORD-RETRY-2"))))
            .andExpect(status().isUnprocessableEntity());

        assertThat(invoiceRepository.findById(invoiceId).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.APPROVED);
        assertThat(paymentRepository.findByPaypalOrderId("ORD-RETRY-2").orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void blocksRevokedExpiredModifiedInvoicesAndOrderSwappingAttacks() throws Exception {
        String sellerA = registerAndGetToken("Seller A", "sellera@test.com");
        String sellerB = registerAndGetToken("Seller B", "sellerb@test.com");

        JsonNode invA = createAndApproveInvoice(sellerA, "Client A", "a@client.test", "200.00", "USD");
        JsonNode invB = createAndApproveInvoice(sellerB, "Client B", "b@client.test", "400.00", "USD");
        long idA = invA.get("id").asLong();
        long idB = invB.get("id").asLong();
        Long userIdA = invoiceRepository.findById(idA).orElseThrow().getUser().getId();
        Long userIdB = invoiceRepository.findById(idB).orElseThrow().getUser().getId();

        String tokenA = paymentLinkService.generateOrRotateLink(userIdA, idA, 14, "127.0.0.1").rawToken();
        String tokenB = paymentLinkService.generateOrRotateLink(userIdB, idB, 14, "127.0.0.1").rawToken();

        when(payPalSandboxClient.createOrder(any())).thenReturn(
            mockOrder("ORD-ONLY-B", "400.00", "USD")
        );
        mockMvc.perform(post("/api/public/pay/" + tokenB + "/orders")).andExpect(status().isCreated());

        // 1. Order-swapping attack: Token A cannot capture or cancel Token B's PayPal order
        mockMvc.perform(post("/api/public/pay/" + tokenA + "/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orderId", "ORD-ONLY-B"))))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/public/pay/" + tokenA + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orderId", "ORD-ONLY-B"))))
            .andExpect(status().isForbidden());
        verify(payPalSandboxClient, never()).captureOrder(any(), any());

        // 2. Editing an approved invoice automatically revokes active payment link A
        mockMvc.perform(put("/api/invoices/" + idA)
                .header("Authorization", "Bearer " + sellerA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", "Client A Updated",
                    "clientEmail", "a@client.test",
                    "description", "Changed scope",
                    "amount", 225.00,
                    "currency", "USD",
                    "dueDate", LocalDate.now().plusDays(14).toString()
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(get("/api/public/pay/" + tokenA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("REVOKED"))
            .andExpect(jsonPath("$.payable").value(false))
            .andExpect(jsonPath("$.amount").isEmpty())
            .andExpect(jsonPath("$.invoiceNumber").isEmpty());

        mockMvc.perform(post("/api/public/pay/" + tokenA + "/orders"))
            .andExpect(status().isGone());

        // 3. Expired link returns EXPIRED redacted view and 410 Gone on checkout
        PaymentLink linkB = paymentLinkRepository.findByTokenHash(paymentLinkService.hashRawTokenOrThrow(tokenB)).orElseThrow();
        linkB.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        paymentLinkRepository.saveAndFlush(linkB);

        mockMvc.perform(get("/api/public/pay/" + tokenB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("EXPIRED"))
            .andExpect(jsonPath("$.payable").value(false))
            .andExpect(jsonPath("$.amount").isEmpty());

        mockMvc.perform(post("/api/public/pay/" + tokenB + "/orders"))
            .andExpect(status().isGone());

        mockMvc.perform(post("/api/public/pay/" + tokenB + "/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("orderId", "ORD-ONLY-B"))))
            .andExpect(status().isGone());
    }

    private PayPalOrderResult mockOrder(String orderId, String amount, String currency) {
        return new PayPalOrderResult(
            orderId,
            "PAYER_ACTION_REQUIRED",
            "https://www.sandbox.paypal.com/checkoutnow?token=" + orderId,
            "payer-action",
            "idem-" + orderId,
            new BigDecimal(amount),
            currency,
            Map.of("orderId", orderId, "status", "PAYER_ACTION_REQUIRED", "sandbox", true)
        );
    }

    private PayPalCaptureResult mockCapture(
            String orderId,
            String captureId,
            String captureStatus,
            boolean completed,
            boolean pending,
            String pendingReason,
            String amount,
            String currency) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("orderId", orderId);
        summary.put("captureId", captureId);
        summary.put("status", captureStatus);
        summary.put("sandbox", true);
        if (pendingReason != null) {
            summary.put("pendingReason", pendingReason);
        }
        return new PayPalCaptureResult(
            orderId,
            "COMPLETED",
            captureId,
            captureStatus,
            completed,
            pending,
            pendingReason,
            new BigDecimal(amount),
            currency,
            "2026-11-11T17:00:00Z",
            summary
        );
    }

    private String registerAndGetToken(String name, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", name,
                    "email", email,
                    "password", "StrongPass!123"
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode createAndApproveInvoice(
            String jwt,
            String clientName,
            String clientEmail,
            String amount,
            String currency) throws Exception {
        MvcResult draftResult = mockMvc.perform(post("/api/invoices")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", clientName,
                    "clientEmail", clientEmail,
                    "description", "Consulting milestone",
                    "amount", new BigDecimal(amount),
                    "currency", currency,
                    "dueDate", LocalDate.now().plusDays(15).toString()
                ))))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode draft = objectMapper.readTree(draftResult.getResponse().getContentAsString());
        long invoiceId = draft.get("id").asLong();
        String snapshotHash = draft.get("snapshotHash").asText();

        MvcResult approvedResult = mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", snapshotHash,
                    "confirmApproval", true
                ))))
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readTree(approvedResult.getResponse().getContentAsString());
    }
}
