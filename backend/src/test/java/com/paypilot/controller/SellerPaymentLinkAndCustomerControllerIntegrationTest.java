package com.paypilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.dto.paypal.PayPalCaptureResult;
import com.paypilot.dto.paypal.PayPalOrderResult;
import com.paypilot.model.Customer;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.repository.AiExtractionLogRepository;
import com.paypilot.repository.AuditLogRepository;
import com.paypilot.repository.CustomerRepository;
import com.paypilot.repository.InvoiceDeliveryRepository;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SellerPaymentLinkAndCustomerControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentLinkRepository paymentLinkRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOrderIntentRepository paymentOrderIntentRepository;

    @Autowired
    private AiExtractionLogRepository extractionLogRepository;

    @Autowired
    private InvoiceDeliveryRepository invoiceDeliveryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockBean
    private PayPalSandboxClient payPalSandboxClient;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        auditLogRepository.deleteAll();
        invoiceDeliveryRepository.deleteAll();
        paymentRepository.deleteAll();
        paymentOrderIntentRepository.deleteAll();
        paymentLinkRepository.deleteAll();
        extractionLogRepository.deleteAll();
        invoiceRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void requiresAuthenticationForSellerLinksCustomersAndUnifiedPayments() throws Exception {
        mockMvc.perform(get("/api/customers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/customers/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A\",\"email\":\"a@b.com\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/invoices/1/payment-links")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/invoices/1/payment-links")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/invoices/1/payment-links/revoke")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
    }

    @Test
    void sellerGeneratesRotatesRevokesPaymentLinksAndEnforcesOwnership() throws Exception {
        String sellerAToken = registerAndGetToken("Seller Alpha", "alpha@seller.test");
        String sellerBToken = registerAndGetToken("Seller Beta", "beta@seller.test");

        JsonNode draftA = createDraft(sellerAToken, "Taylor", "taylor@client.test", "+14155551010", "500.00");
        long invoiceAId = draftA.get("id").asLong();

        // DRAFT invoice cannot generate a payment link
        mockMvc.perform(post("/api/invoices/" + invoiceAId + "/payment-links")
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isConflict());

        // Approve invoice A
        approveInvoice(sellerAToken, invoiceAId, draftA.get("snapshotHash").asText());

        // Non-owner cannot generate, list, or revoke
        mockMvc.perform(post("/api/invoices/" + invoiceAId + "/payment-links")
                .header("Authorization", "Bearer " + sellerBToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/invoices/" + invoiceAId + "/payment-links")
                .header("Authorization", "Bearer " + sellerBToken))
            .andExpect(status().isForbidden());

        // Generate first link
        MvcResult firstRes = mockMvc.perform(post("/api/invoices/" + invoiceAId + "/payment-links")
                .header("Authorization", "Bearer " + sellerAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("expiresInDays", 7))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.rawToken").isNotEmpty())
            .andExpect(jsonPath("$.paymentUrl").isNotEmpty())
            .andExpect(jsonPath("$.link.active").value(true))
            .andExpect(jsonPath("$.link.tokenPrefix").isNotEmpty())
            .andReturn();

        String firstRawToken = objectMapper.readTree(firstRes.getResponse().getContentAsString()).get("rawToken").asText();

        // History endpoint never leaks rawToken or tokenHash
        MvcResult historyRes1 = mockMvc.perform(get("/api/invoices/" + invoiceAId + "/payment-links")
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].active").value(true))
            .andReturn();
        assertThat(historyRes1.getResponse().getContentAsString())
            .doesNotContain(firstRawToken)
            .doesNotContain("tokenHash");

        // Rotate link -> revokes previous link automatically and issues a single new active link
        MvcResult rotateRes = mockMvc.perform(post("/api/invoices/" + invoiceAId + "/payment-links")
                .header("Authorization", "Bearer " + sellerAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("expiresInDays", 30))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.link.active").value(true))
            .andReturn();
        String secondRawToken = objectMapper.readTree(rotateRes.getResponse().getContentAsString()).get("rawToken").asText();
        assertThat(secondRawToken).isNotEqualTo(firstRawToken);

        // Old link is REVOKED publicly; new link is ACTIVE publicly
        mockMvc.perform(get("/api/public/pay/" + firstRawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("REVOKED"));
        mockMvc.perform(get("/api/public/pay/" + secondRawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("ACTIVE"));

        // History now shows 2 entries: 1 active, 1 revoked with ROTATED_BY_SELLER
        mockMvc.perform(get("/api/invoices/" + invoiceAId + "/payment-links")
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
        assertThat(paymentLinkRepository.findByActiveInvoiceId(invoiceAId)).isPresent();

        // Non-owner cannot revoke; owner revokes cleanly
        mockMvc.perform(post("/api/invoices/" + invoiceAId + "/payment-links/revoke")
                .header("Authorization", "Bearer " + sellerBToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/invoices/" + invoiceAId + "/payment-links/revoke")
                .header("Authorization", "Bearer " + sellerAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "Replaced manually"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked").value(true))
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.revocationReason").value("Replaced manually"));

        assertThat(paymentLinkRepository.findByActiveInvoiceId(invoiceAId)).isEmpty();
        mockMvc.perform(get("/api/public/pay/" + secondRawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkState").value("REVOKED"));
    }

    @Test
    void syncsCustomersFromInvoicesValidatesPhonePreservesApprovedSnapshotAndIsolatesSellers() throws Exception {
        String sellerAToken = registerAndGetToken("Seller One", "seller1@paypilot.test");
        String sellerBToken = registerAndGetToken("Seller Two", "seller2@paypilot.test");

        // Seed a legacy-like invoice without customer_id in Seller A's account
        Long sellerAId = userRepository.findByEmail("seller1@paypilot.test").orElseThrow().getId();
        invoiceRepository.saveAndFlush(Invoice.builder()
            .user(userRepository.findById(sellerAId).orElseThrow())
            .invoiceNumber("INV-LEGACY-01")
            .clientName("Legacy Buyer")
            .clientEmail("legacy@buyer.test")
            .description("Prior work")
            .amount(new BigDecimal("220.00"))
            .currency("USD")
            .dueDate(LocalDate.now().plusDays(10))
            .status(InvoiceStatus.DRAFT)
            .build());

        // Create and approve a new invoice without phone initially
        JsonNode approvedA = createDraft(sellerAToken, "Nina Patel", "nina@acme.test", null, "750.00");
        long invoiceAId = approvedA.get("id").asLong();
        String originalHash = approvedA.get("snapshotHash").asText();
        approveInvoice(sellerAToken, invoiceAId, originalHash);

        // Listing Seller A customers backfills Legacy Buyer and exposes Nina Patel cleanly
        MvcResult listA = mockMvc.perform(get("/api/customers")
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andReturn();

        JsonNode customersA = objectMapper.readTree(listA.getResponse().getContentAsString());
        long ninaCustomerId = -1L;
        for (JsonNode c : customersA) {
            if ("nina@acme.test".equals(c.get("email").asText())) {
                ninaCustomerId = c.get("id").asLong();
            }
        }
        assertThat(ninaCustomerId).isPositive();

        // Seller B sees zero of Seller A's customers and receives 403 accessing Seller A's customer ID
        mockMvc.perform(get("/api/customers")
                .header("Authorization", "Bearer " + sellerBToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/customers/" + ninaCustomerId)
                .header("Authorization", "Bearer " + sellerBToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/customers/" + ninaCustomerId)
                .header("Authorization", "Bearer " + sellerBToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Hacked",
                    "email", "nina@acme.test",
                    "phone", "+14155559999"
                ))))
            .andExpect(status().isForbidden());

        // Reject invalid phone missing country code
        mockMvc.perform(put("/api/customers/" + ninaCustomerId)
                .header("Authorization", "Bearer " + sellerAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Nina Patel",
                    "email", "nina@acme.test",
                    "phone", "4155551234"
                ))))
            .andExpect(status().isBadRequest());

        // Valid E.164 phone updates Customer and syncs clientPhone onto APPROVED invoice WITHOUT invalidating approval/snapshot
        mockMvc.perform(put("/api/customers/" + ninaCustomerId)
                .header("Authorization", "Bearer " + sellerAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Nina Patel",
                    "email", "nina@acme.test",
                    "phone", "+1 (415) 555-2671",
                    "company", "Acme Corp",
                    "notes", "Prefers WhatsApp reminders"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phone").value("+14155552671"))
            .andExpect(jsonPath("$.company").value("Acme Corp"));

        mockMvc.perform(get("/api/invoices/" + invoiceAId)
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.clientPhone").value("+14155552671"))
            .andExpect(jsonPath("$.approvedSnapshotHash").value(originalHash));

        // Link generation + customer lookup still work seamlessly
        Customer synced = customerRepository.findById(ninaCustomerId).orElseThrow();
        assertThat(synced.getPhone()).isEqualTo("+14155552671");

        mockMvc.perform(get("/api/customers/" + ninaCustomerId)
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customer.phone").value("+14155552671"))
            .andExpect(jsonPath("$.invoices.length()").value(1));

        // Seller B cannot assign Seller A's customerId when creating an invoice
        mockMvc.perform(post("/api/invoices")
                .header("Authorization", "Bearer " + sellerBToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", "Spoofed",
                    "clientEmail", "spoof@test.com",
                    "description", "Should fail",
                    "amount", 100.00,
                    "currency", "USD",
                    "dueDate", LocalDate.now().plusDays(7).toString(),
                    "customerId", ninaCustomerId
                ))))
            .andExpect(status().isForbidden());
    }

    @Test
    void listsUnifiedSellerPaymentsWithStatusFilterAndProvenance() throws Exception {
        String sellerAToken = registerAndGetToken("Pay Merchant A", "paymerchA@test.com");
        String sellerBToken = registerAndGetToken("Pay Merchant B", "paymerchB@test.com");

        JsonNode inv1 = createDraft(sellerAToken, "Client One", "c1@test.com", "+14155550001", "400.00");
        long inv1Id = inv1.get("id").asLong();
        approveInvoice(sellerAToken, inv1Id, inv1.get("snapshotHash").asText());

        JsonNode inv2 = createDraft(sellerAToken, "Client Two", "c2@test.com", "+14155550002", "150.00");
        long inv2Id = inv2.get("id").asLong();
        approveInvoice(sellerAToken, inv2Id, inv2.get("snapshotHash").asText());

        MvcResult linkRes = mockMvc.perform(post("/api/invoices/" + inv1Id + "/payment-links")
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isCreated())
            .andReturn();
        String rawToken = objectMapper.readTree(linkRes.getResponse().getContentAsString()).get("rawToken").asText();

        when(payPalSandboxClient.createOrder(any())).thenReturn(
            new PayPalOrderResult(
                "ORD-LINK-PAY-1", "PAYER_ACTION_REQUIRED",
                "https://www.sandbox.paypal.com/checkoutnow?token=ORD-LINK-PAY-1",
                "payer-action", "idem-1", new BigDecimal("400.00"), "USD",
                Map.of("orderId", "ORD-LINK-PAY-1")
            ),
            new PayPalOrderResult(
                "ORD-SELLER-PAY-2", "PAYER_ACTION_REQUIRED",
                "https://www.sandbox.paypal.com/checkoutnow?token=ORD-SELLER-PAY-2",
                "payer-action", "idem-2", new BigDecimal("150.00"), "USD",
                Map.of("orderId", "ORD-SELLER-PAY-2")
            )
        );

        // Customer pays inv1 via link (CAPTURED)
        mockMvc.perform(post("/api/public/pay/" + rawToken + "/orders")).andExpect(status().isCreated());
        when(payPalSandboxClient.captureOrder(eq("ORD-LINK-PAY-1"), any())).thenReturn(
            new PayPalCaptureResult(
                "ORD-LINK-PAY-1", "COMPLETED", "CAP-LINK-1", "COMPLETED",
                true, false, null, new BigDecimal("400.00"), "USD", "2026-11-12T08:00:00Z",
                Map.of("captureId", "CAP-LINK-1", "captureStatus", "COMPLETED")
            )
        );
        mockMvc.perform(post("/api/public/pay/" + rawToken + "/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"ORD-LINK-PAY-1\"}"))
            .andExpect(status().isOk());

        // Seller creates & captures inv2 with PENDING status
        mockMvc.perform(post("/api/payments/orders")
                .header("Authorization", "Bearer " + sellerAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("invoiceId", inv2Id))))
            .andExpect(status().isCreated());
        when(payPalSandboxClient.captureOrder(eq("ORD-SELLER-PAY-2"), any())).thenReturn(
            new PayPalCaptureResult(
                "ORD-SELLER-PAY-2", "COMPLETED", "CAP-PEND-2", "PENDING",
                false, true, "PENDING_REVIEW", new BigDecimal("150.00"), "USD", null,
                Map.of("captureId", "CAP-PEND-2", "captureStatus", "PENDING", "pendingReason", "PENDING_REVIEW")
            )
        );
        mockMvc.perform(post("/api/payments/orders/ORD-SELLER-PAY-2/capture")
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isAccepted());

        // Seller A sees both payments with correct provenance
        mockMvc.perform(get("/api/payments")
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        // Status filter returns only PENDING
        mockMvc.perform(get("/api/payments?status=PENDING")
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].paypalOrderId").value("ORD-SELLER-PAY-2"))
            .andExpect(jsonPath("$[0].customerLinkCheckout").value(false))
            .andExpect(jsonPath("$[0].clientName").value("Client Two"));

        mockMvc.perform(get("/api/payments?status=CAPTURED")
                .header("Authorization", "Bearer " + sellerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].paypalOrderId").value("ORD-LINK-PAY-1"))
            .andExpect(jsonPath("$[0].customerLinkCheckout").value(true))
            .andExpect(jsonPath("$[0].clientName").value("Client One"));

        // Seller B sees zero payments
        mockMvc.perform(get("/api/payments")
                .header("Authorization", "Bearer " + sellerBToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
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

    private JsonNode createDraft(
            String jwt,
            String clientName,
            String clientEmail,
            String clientPhone,
            String amount) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("clientName", clientName);
        body.put("clientEmail", clientEmail);
        if (clientPhone != null) {
            body.put("clientPhone", clientPhone);
        }
        body.put("description", "Development & consulting services");
        body.put("amount", new BigDecimal(amount));
        body.put("currency", "USD");
        body.put("dueDate", LocalDate.now().plusDays(14).toString());

        MvcResult result = mockMvc.perform(post("/api/invoices")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void approveInvoice(String jwt, long invoiceId, String snapshotHash) throws Exception {
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/approve")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "snapshotHash", snapshotHash,
                    "confirmApproval", true
                ))))
            .andExpect(status().isOk());
    }
}

