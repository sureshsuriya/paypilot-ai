package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.email.InvoiceEmailPreviewResponse;
import com.paypilot.dto.email.InvoiceEmailRequest;
import com.paypilot.exception.EmailDeliveryException;
import com.paypilot.exception.InvalidInvoiceStateException;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceDelivery;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.PaymentLink;
import com.paypilot.model.User;
import com.paypilot.repository.InvoiceDeliveryRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceEmailServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private InvoiceDeliveryRepository invoiceDeliveryRepository;
    @Mock
    private PaymentLinkService paymentLinkService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    private InvoiceEmailService disabledMailService;
    private User seller;
    private Invoice approvedInvoice;
    private PaymentLink activeLink;

    @BeforeEach
    void setUp() {
        InvoiceSnapshotService snapshotService = new InvoiceSnapshotService();
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
        disabledMailService = new InvoiceEmailService(
            invoiceRepository,
            paymentLinkRepository,
            paymentRepository,
            invoiceDeliveryRepository,
            paymentLinkService,
            snapshotService,
            auditLogService,
            transactionManager,
            mailSenderProvider,
            clock,
            false,
            "",
            "no-reply@paypilot.test",
            60,
            "http://localhost:5173"
        );

        seller = User.builder()
            .id(11L)
            .email("seller@paypilot.test")
            .name("Northstar <Studio>")
            .passwordHash("hash")
            .build();

        approvedInvoice = Invoice.builder()
            .id(55L)
            .user(seller)
            .invoiceNumber("INV-20261112-EM01")
            .clientName("Alex <script>alert(1)</script>")
            .clientEmail("alex@buyer.test")
            .description("Design & consulting <b>work</b>")
            .amount(new BigDecimal("750.00"))
            .currency("USD")
            .dueDate(LocalDate.of(2026, 11, 25))
            .status(InvoiceStatus.APPROVED)
            .approvedAt(LocalDateTime.of(2026, 11, 12, 9, 0))
            .approvedByUserId(11L)
            .build();
        approvedInvoice.setApprovedSnapshotHash(snapshotService.computeHash(approvedInvoice));

        activeLink = PaymentLink.builder()
            .id(88L)
            .invoice(approvedInvoice)
            .user(seller)
            .activeInvoiceId(55L)
            .tokenHash("c".repeat(64))
            .tokenPrefix("ppl_Token1...")
            .invoiceSnapshotHash(approvedInvoice.getApprovedSnapshotHash())
            .expiresAt(LocalDateTime.of(2026, 11, 26, 10, 0))
            .build();
    }

    @Test
    void escapesHtmlInPreviewAndRejectsSendCleanlyWhenSmtpDisabled() {
        String rawToken = "ValidTokenStringForEmailTest123456789012345678";
        when(invoiceRepository.findById(55L)).thenReturn(Optional.of(approvedInvoice));
        when(paymentRepository.existsByInvoiceIdAndStatus(55L, com.paypilot.model.PaymentStatus.CAPTURED)).thenReturn(false);
        when(paymentRepository.existsByInvoiceIdAndStatus(55L, com.paypilot.model.PaymentStatus.PENDING)).thenReturn(false);
        when(paymentLinkRepository.findByActiveInvoiceId(55L)).thenReturn(Optional.of(activeLink));
        when(paymentLinkService.verifyRawTokenMatchesLink(rawToken, activeLink)).thenReturn(true);

        InvoiceEmailPreviewResponse preview = disabledMailService.previewInvoiceEmail(
            11L,
            55L,
            new InvoiceEmailRequest(rawToken, null, "Note <img src=x onerror=alert(1)>")
        );

        assertThat(preview.mailConfigured()).isFalse();
        assertThat(preview.recipientEmail()).isEqualTo("alex@buyer.test");
        assertThat(preview.paymentUrl()).isEqualTo("http://localhost:5173/pay/" + rawToken);
        assertThat(preview.htmlBody())
            .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
            .contains("&lt;img src=x onerror=alert(1)&gt;")
            .doesNotContain("<script>");

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(invoiceRepository.getReferenceById(55L)).thenReturn(approvedInvoice);
        when(paymentLinkRepository.getReferenceById(88L)).thenReturn(activeLink);
        when(invoiceDeliveryRepository.saveAndFlush(any(InvoiceDelivery.class))).thenAnswer(inv -> {
            InvoiceDelivery d = inv.getArgument(0);
            d.setId(901L);
            return d;
        });

        assertThatThrownBy(() -> disabledMailService.sendInvoiceEmail(
            11L,
            55L,
            new InvoiceEmailRequest(rawToken, "alex@buyer.test", null),
            "127.0.0.1"
        ))
            .isInstanceOf(EmailDeliveryException.class)
            .hasMessageContaining("MAIL_ENABLED");

        verify(invoiceDeliveryRepository).saveAndFlush(any(InvoiceDelivery.class));
    }

    @Test
    void rejectsPreviewWhenRawTokenDoesNotMatchActiveLink() {
        when(invoiceRepository.findById(55L)).thenReturn(Optional.of(approvedInvoice));
        when(paymentRepository.existsByInvoiceIdAndStatus(55L, com.paypilot.model.PaymentStatus.CAPTURED)).thenReturn(false);
        when(paymentRepository.existsByInvoiceIdAndStatus(55L, com.paypilot.model.PaymentStatus.PENDING)).thenReturn(false);
        when(paymentLinkRepository.findByActiveInvoiceId(55L)).thenReturn(Optional.of(activeLink));
        when(paymentLinkService.verifyRawTokenMatchesLink("stale-token", activeLink)).thenReturn(false);

        assertThatThrownBy(() -> disabledMailService.previewInvoiceEmail(
            11L, 55L, new InvoiceEmailRequest("stale-token", null, null)
        ))
            .isInstanceOf(InvalidInvoiceStateException.class);
    }
}

