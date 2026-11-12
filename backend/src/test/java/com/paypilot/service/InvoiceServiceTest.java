package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.invoice.CreateInvoiceRequest;
import com.paypilot.dto.invoice.InvoiceResponse;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.User;
import com.paypilot.model.UserRole;
import com.paypilot.repository.AiExtractionLogRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentOrderIntentRepository;
import com.paypilot.repository.PaymentRepository;
import com.paypilot.repository.UserRepository;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentOrderIntentRepository paymentOrderIntentRepository;
    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private AiExtractionLogRepository extractionLogRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private CustomerService customerService;
    @Spy
    private InvoiceSnapshotService invoiceSnapshotService = new InvoiceSnapshotService();
    @Mock
    private Validator validator;
    @Spy
    private Clock paymentClock = Clock.systemDefaultZone();

    @InjectMocks
    private InvoiceService invoiceService;

    @Test
    void retriesWhenGeneratedInvoiceNumberCollides() {
        User owner = User.builder().id(1L).email("u@test.com").name("U").passwordHash("h").role(UserRole.USER).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(invoiceRepository.existsByInvoiceNumberAndUserId(any(), eq(1L)))
            .thenReturn(true, false);
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> {
            Invoice i = inv.getArgument(0);
            i.setId(10L);
            return i;
        });

        CreateInvoiceRequest req = new CreateInvoiceRequest(
            "Alex", "alex@test.com", "Website development",
            new BigDecimal("750"), "usd", LocalDate.now().plusDays(7)
        );

        InvoiceResponse res = invoiceService.createInvoice(1L, req, "127.0.0.1");

        assertThat(res.id()).isEqualTo(10L);
        assertThat(res.amount()).isEqualByComparingTo("750.00");
        assertThat(res.currency()).isEqualTo("USD");
        assertThat(res.status()).isEqualTo(InvoiceStatus.DRAFT);
        verify(invoiceRepository, times(2)).existsByInvoiceNumberAndUserId(any(), eq(1L));
    }

    @Test
    void throwsIfUniqueInvoiceNumberExhaustsMaxAttempts() {
        User owner = User.builder().id(1L).email("u@test.com").name("U").passwordHash("h").role(UserRole.USER).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(invoiceRepository.existsByInvoiceNumberAndUserId(any(), eq(1L))).thenReturn(true);

        CreateInvoiceRequest req = new CreateInvoiceRequest(
            "Alex", "alex@test.com", "Website development",
            new BigDecimal("750.00"), "USD", LocalDate.now().plusDays(7)
        );

        assertThatThrownBy(() -> invoiceService.createInvoice(1L, req, "127.0.0.1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unique invoice number");
    }
}

