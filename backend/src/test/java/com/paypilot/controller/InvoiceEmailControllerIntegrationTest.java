package com.paypilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.model.AuditLog;
import com.paypilot.model.InvoiceDelivery;
import com.paypilot.repository.AiExtractionLogRepository;
import com.paypilot.repository.AuditLogRepository;
import com.paypilot.repository.CustomerRepository;
import com.paypilot.repository.InvoiceDeliveryRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentOrderIntentRepository;
import com.paypilot.repository.PaymentRepository;
import com.paypilot.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "paypilot.mail.enabled=true",
    "spring.mail.host=smtp.paypilot.test",
    "paypilot.mail.from=billing@paypilot.test",
    "paypilot.mail.resend-cooldown-seconds=60"
})
@AutoConfigureMockMvc
class InvoiceEmailControllerIntegrationTest {

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
    private InvoiceDeliveryRepository invoiceDeliveryRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOrderIntentRepository paymentOrderIntentRepository;

    @Autowired
    private AiExtractionLogRepository extractionLogRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockBean
    private JavaMailSender javaMailSender;

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
    void enforcesAuthOwnershipValidationCooldownAndMockedSmtpDeliveryWithoutLeakingRawToken() throws Exception {
        mockMvc.perform(post("/api/invoices/1/email/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawToken\":\"abc\"}"))
            .andExpect(status().isUnauthorized());

        String sellerToken = registerAndGetToken("Apex Creative", "apex@seller.test");
        String intruderToken = registerAndGetToken("Other Seller", "other@seller.test");

        JsonNode draft = createDraft(sellerToken, "Taylor Buyer", "taylor@client.test", "750.00");
        long invoiceId = draft.get("id").asLong();

        // Unapproved DRAFT cannot preview email
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/email/preview")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rawToken", "missingLinkToken12345678901234567890"))))
            .andExpect(status().isConflict());

        // Approve invoice & generate active link
        approveInvoice(sellerToken, invoiceId, draft.get("snapshotHash").asText());
        MvcResult linkRes = mockMvc.perform(post("/api/invoices/" + invoiceId + "/payment-links")
                .header("Authorization", "Bearer " + sellerToken))
            .andExpect(status().isCreated())
            .andReturn();
        String rawToken = objectMapper.readTree(linkRes.getResponse().getContentAsString()).get("rawToken").asText();

        // Non-owner cannot preview or send email
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/email/preview")
                .header("Authorization", "Bearer " + intruderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rawToken", rawToken))))
            .andExpect(status().isForbidden());

        // Seller previews email cleanly (HTML & text rendered safely)
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/email/preview")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "rawToken", rawToken,
                    "customMessage", "Thanks for working with us!"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipientEmail").value("taylor@client.test"))
            .andExpect(jsonPath("$.mailConfigured").value(true))
            .andExpect(jsonPath("$.paymentUrl").value("http://localhost:5173/pay/" + rawToken));

        // Invalid recipient email rejected (400 Bad Request)
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/email/send")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "rawToken", rawToken,
                    "recipientEmail", "not-an-email"
                ))))
            .andExpect(status().isBadRequest());

        when(javaMailSender.createMimeMessage()).thenAnswer(
            inv -> new MimeMessage(Session.getInstance(new Properties()))
        );

        // First SMTP failure records FAILED cleanly and returns 503
        doThrow(new MailSendException("Simulated SMTP connection refused"))
            .when(javaMailSender).send(any(MimeMessage.class));

        mockMvc.perform(post("/api/invoices/" + invoiceId + "/email/send")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rawToken", rawToken))))
            .andExpect(status().isServiceUnavailable());

        // Subsequent send succeeds cleanly without being blocked by the prior FAILED attempt
        doNothing().when(javaMailSender).send(any(MimeMessage.class));

        mockMvc.perform(post("/api/invoices/" + invoiceId + "/email/send")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "rawToken", rawToken,
                    "customMessage", "Please complete Sandbox checkout before the due date."
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipientEmail").value("taylor@client.test"))
            .andExpect(jsonPath("$.sandboxMode").value(true));

        verify(javaMailSender, times(2)).send(any(MimeMessage.class));

        // Immediate duplicate send blocked by cooldown guard (409 Conflict)
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/email/send")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rawToken", rawToken))))
            .andExpect(status().isConflict());

        // Delivery log & audit log contain SENT + FAILED records and zero rawToken leaks
        mockMvc.perform(get("/api/invoices/" + invoiceId + "/deliveries")
                .header("Authorization", "Bearer " + sellerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        List<InvoiceDelivery> deliveries = invoiceDeliveryRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId);
        assertThat(deliveries).extracting(InvoiceDelivery::getStatus).containsExactly("SENT", "FAILED");

        List<AuditLog> audits = auditLogRepository.findAll();
        assertThat(audits).extracting(AuditLog::getAction).contains("INVOICE_EMAIL_SENT", "INVOICE_EMAIL_FAILED");
        assertThat(audits).allSatisfy(log -> assertThat(String.valueOf(log.getDetails())).doesNotContain(rawToken));
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

    private JsonNode createDraft(String jwt, String clientName, String clientEmail, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invoices")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "clientName", clientName,
                    "clientEmail", clientEmail,
                    "description", "Email delivery test invoice",
                    "amount", new BigDecimal(amount),
                    "currency", "USD",
                    "dueDate", LocalDate.now().plusDays(14).toString()
                ))))
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

