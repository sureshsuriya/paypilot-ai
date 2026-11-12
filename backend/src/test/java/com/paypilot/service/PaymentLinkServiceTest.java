package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.paymentlink.GeneratedPaymentLinkResponse;
import com.paypilot.dto.paymentlink.PaymentLinkSummaryResponse;
import com.paypilot.exception.ForbiddenOperationException;
import com.paypilot.exception.InvalidInvoiceStateException;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.PaymentLink;
import com.paypilot.model.User;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentLinkServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private AuditLogService auditLogService;

    private InvoiceSnapshotService snapshotService;
    private Clock fixedClock;
    private PaymentLinkService paymentLinkService;

    private User seller;
    private Invoice approvedInvoice;

    @BeforeEach
    void setUp() {
        snapshotService = new InvoiceSnapshotService();
        fixedClock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
        paymentLinkService = new PaymentLinkService(
            paymentLinkRepository,
            invoiceRepository,
            paymentRepository,
            snapshotService,
            auditLogService,
            fixedClock,
            14,
            168,
            "http://localhost:5173"
        );

        seller = User.builder()
            .id(7L)
            .email("seller@paypilot.test")
            .name("Northstar Studio")
            .passwordHash("hash")
            .build();

        approvedInvoice = Invoice.builder()
            .id(42L)
            .user(seller)
            .invoiceNumber("INV-20261111-UNIT01")
            .clientName("Alex Client")
            .clientEmail("alex@client.test")
            .description("Web platform architecture")
            .amount(new BigDecimal("750.00"))
            .currency("USD")
            .dueDate(LocalDate.of(2026, 11, 25))
            .status(InvoiceStatus.APPROVED)
            .approvedAt(LocalDateTime.of(2026, 11, 11, 11, 0))
            .approvedByUserId(7L)
            .build();
        approvedInvoice.setApprovedSnapshotHash(snapshotService.computeHash(approvedInvoice));
    }

    @Test
    void generates256BitTokenStoresOnlySha256HashAndRotatesPreviousLink() {
        when(invoiceRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(approvedInvoice));
        when(paymentRepository.existsByInvoiceIdAndStatus(eqId(42L), any())).thenReturn(false);

        PaymentLink previous = PaymentLink.builder()
            .id(100L)
            .invoice(approvedInvoice)
            .user(seller)
            .activeInvoiceId(42L)
            .tokenHash("a".repeat(64))
            .tokenPrefix("ppl_aaaaaa...")
            .invoiceSnapshotHash(approvedInvoice.getApprovedSnapshotHash())
            .expiresAt(LocalDateTime.of(2026, 11, 20, 12, 0))
            .build();

        when(paymentLinkRepository.findByActiveInvoiceId(42L)).thenReturn(Optional.of(previous));
        when(paymentLinkRepository.existsByTokenHash(any())).thenReturn(false);

        AtomicReference<PaymentLink> createdRef = new AtomicReference<>();
        when(paymentLinkRepository.saveAndFlush(any(PaymentLink.class))).thenAnswer(inv -> {
            PaymentLink arg = inv.getArgument(0);
            if (arg.getId() == null) {
                arg.setId(101L);
                arg.setCreatedAt(LocalDateTime.now(fixedClock));
                createdRef.set(arg);
            }
            return arg;
        });

        GeneratedPaymentLinkResponse generated = paymentLinkService.generateOrRotateLink(
            7L, 42L, 7, "127.0.0.1"
        );

        assertThat(generated.rawToken()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(generated.paymentUrl()).isEqualTo("http://localhost:5173/pay/" + generated.rawToken());
        assertThat(generated.link().id()).isEqualTo(101L);
        assertThat(generated.link().active()).isTrue();
        assertThat(generated.link().expiresAt()).isEqualTo(LocalDateTime.of(2026, 11, 18, 12, 0));

        assertThat(previous.isRevoked()).isTrue();
        assertThat(previous.getActiveInvoiceId()).isNull();
        assertThat(previous.getRevocationReason()).isEqualTo("ROTATED_BY_SELLER");

        PaymentLink persisted = createdRef.get();
        assertThat(persisted.getTokenHash()).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(persisted.getTokenHash()).isNotEqualTo(generated.rawToken());
        assertThat(persisted.getTokenHash()).isEqualTo(paymentLinkService.hashRawTokenOrThrow(generated.rawToken()));
        assertThat(paymentLinkService.verifyRawTokenMatchesLink(generated.rawToken(), persisted)).isTrue();
        assertThat(paymentLinkService.verifyRawTokenMatchesLink("tampered_token_value_12345678901234567890123", persisted)).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditLogService).logEvent(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("PAYMENT_LINK"),
            org.mockito.ArgumentMatchers.eq(101L),
            org.mockito.ArgumentMatchers.eq("PAYMENT_LINK_ROTATED"),
            any(),
            detailsCaptor.capture(),
            org.mockito.ArgumentMatchers.eq("127.0.0.1")
        );
        assertThat(detailsCaptor.getValue().toString()).doesNotContain(generated.rawToken());
    }

    @Test
    void rejectsNonOwnerDraftInvoiceAndInvalidTtlBounds() {
        when(invoiceRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(approvedInvoice));

        assertThatThrownBy(() -> paymentLinkService.generateOrRotateLink(999L, 42L, 7, "127.0.0.1"))
            .isInstanceOf(ForbiddenOperationException.class);

        assertThatThrownBy(() -> paymentLinkService.generateOrRotateLink(7L, 42L, 0, "127.0.0.1"))
            .isInstanceOf(IllegalArgumentException.class);

        approvedInvoice.setStatus(InvoiceStatus.DRAFT);
        assertThatThrownBy(() -> paymentLinkService.generateOrRotateLink(7L, 42L, 7, "127.0.0.1"))
            .isInstanceOf(InvalidInvoiceStateException.class);
    }

    @Test
    void revokesActiveLinkAndRejectsMissingActiveLink() {
        when(invoiceRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(approvedInvoice));
        when(paymentLinkRepository.findByActiveInvoiceId(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentLinkService.revokeActiveLink(7L, 42L, "Test", "127.0.0.1"))
            .isInstanceOf(InvalidInvoiceStateException.class);

        PaymentLink active = PaymentLink.builder()
            .id(105L)
            .invoice(approvedInvoice)
            .user(seller)
            .activeInvoiceId(42L)
            .tokenHash("b".repeat(64))
            .tokenPrefix("ppl_bbbbbb...")
            .invoiceSnapshotHash(approvedInvoice.getApprovedSnapshotHash())
            .expiresAt(LocalDateTime.of(2026, 11, 25, 12, 0))
            .createdAt(LocalDateTime.of(2026, 11, 11, 12, 0))
            .build();
        when(paymentLinkRepository.findByActiveInvoiceId(42L)).thenReturn(Optional.of(active));
        when(paymentLinkRepository.saveAndFlush(active)).thenReturn(active);

        PaymentLinkSummaryResponse summary = paymentLinkService.revokeActiveLink(7L, 42L, "No longer needed", "127.0.0.1");
        assertThat(summary.revoked()).isTrue();
        assertThat(summary.active()).isFalse();
        assertThat(summary.revocationReason()).isEqualTo("No longer needed");
    }

    private static Long eqId(Long id) {
        return org.mockito.ArgumentMatchers.eq(id);
    }
}

