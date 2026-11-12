package com.paypilot.repository;

import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentLink;
import com.paypilot.model.PaymentStatus;
import com.paypilot.model.User;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class PaymentLinkRepositoryTest {

    @Autowired
    private PaymentLinkRepository paymentLinkRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User seller;
    private Invoice approvedInvoice;
    private static final String SNAPSHOT_HASH_1 = "a".repeat(64);
    private static final String TOKEN_HASH_1 = "1".repeat(64);
    private static final String TOKEN_HASH_2 = "2".repeat(64);

    @BeforeEach
    void setUp() {
        seller = userRepository.saveAndFlush(User.builder()
            .email("seller-links@paypilot.test")
            .name("Seller Links")
            .passwordHash("hashed")
            .build());

        approvedInvoice = invoiceRepository.saveAndFlush(Invoice.builder()
            .user(seller)
            .invoiceNumber("INV-LINK-001")
            .clientName("Acme Buyer")
            .clientEmail("buyer@acme.test")
            .description("Backend API integration milestone")
            .amount(new BigDecimal("750.00"))
            .currency("USD")
            .dueDate(LocalDate.of(2026, 12, 1))
            .status(InvoiceStatus.APPROVED)
            .approvedAt(LocalDateTime.of(2026, 11, 11, 10, 0))
            .approvedByUserId(seller.getId())
            .approvedSnapshotHash(SNAPSHOT_HASH_1)
            .build());
    }

    @Test
    void savesPaymentLinkAndLinksOptionalPaymentProvenance() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 11, 25, 23, 59);
        PaymentLink link = paymentLinkRepository.saveAndFlush(PaymentLink.builder()
            .invoice(approvedInvoice)
            .user(seller)
            .tokenHash(TOKEN_HASH_1)
            .tokenPrefix("ppl_11111111")
            .invoiceSnapshotHash(SNAPSHOT_HASH_1)
            .expiresAt(expiresAt)
            .build());

        Payment linkedPayment = paymentRepository.saveAndFlush(Payment.builder()
            .invoice(approvedInvoice)
            .paymentLink(link)
            .paypalOrderId("ORDER-LINK-1")
            .amount(new BigDecimal("750.00"))
            .currency("USD")
            .status(PaymentStatus.CREATED)
            .build());

        entityManager.clear();

        PaymentLink reloaded = paymentLinkRepository.findByTokenHash(TOKEN_HASH_1).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(link.getId());
        assertThat(reloaded.getActiveInvoiceId()).isEqualTo(approvedInvoice.getId());
        assertThat(reloaded.getTokenPrefix()).isEqualTo("ppl_11111111");
        assertThat(reloaded.getInvoiceSnapshotHash()).isEqualTo(SNAPSHOT_HASH_1);
        assertThat(reloaded.getAccessCount()).isZero();
        assertThat(reloaded.isRevoked()).isFalse();
        assertThat(paymentLinkRepository.existsByTokenHash(TOKEN_HASH_1)).isTrue();
        assertThat(paymentLinkRepository.findByActiveInvoiceId(approvedInvoice.getId())).isPresent();

        Payment reloadedPayment = paymentRepository.findById(linkedPayment.getId()).orElseThrow();
        assertThat(reloadedPayment.getPaymentLink()).isNotNull();
        assertThat(reloadedPayment.getPaymentLink().getId()).isEqualTo(link.getId());
    }

    @Test
    void enforcesUniqueTokenHashAcrossInvoices() {
        Invoice secondInvoice = invoiceRepository.saveAndFlush(Invoice.builder()
            .user(seller)
            .invoiceNumber("INV-LINK-002")
            .clientName("Second Buyer")
            .clientEmail("second@buyer.test")
            .amount(new BigDecimal("120.00"))
            .currency("USD")
            .status(InvoiceStatus.APPROVED)
            .approvedSnapshotHash("c".repeat(64))
            .build());

        LocalDateTime expiresAt = LocalDateTime.of(2026, 11, 25, 23, 59);
        paymentLinkRepository.saveAndFlush(PaymentLink.builder()
            .invoice(approvedInvoice)
            .user(seller)
            .tokenHash(TOKEN_HASH_1)
            .tokenPrefix("ppl_11111111")
            .invoiceSnapshotHash(SNAPSHOT_HASH_1)
            .expiresAt(expiresAt)
            .build());

        PaymentLink duplicateToken = PaymentLink.builder()
            .invoice(secondInvoice)
            .user(seller)
            .tokenHash(TOKEN_HASH_1)
            .tokenPrefix("ppl_11111111")
            .invoiceSnapshotHash("c".repeat(64))
            .expiresAt(expiresAt)
            .build();

        assertThatThrownBy(() -> paymentLinkRepository.saveAndFlush(duplicateToken))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesSingleActiveLinkPerInvoiceAndAllowsReplacementAfterRevocation() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 11, 25, 23, 59);
        PaymentLink first = paymentLinkRepository.saveAndFlush(PaymentLink.builder()
            .invoice(approvedInvoice)
            .user(seller)
            .tokenHash(TOKEN_HASH_1)
            .tokenPrefix("ppl_11111111")
            .invoiceSnapshotHash(SNAPSHOT_HASH_1)
            .expiresAt(expiresAt)
            .build());

        PaymentLink competingActive = PaymentLink.builder()
            .invoice(approvedInvoice)
            .user(seller)
            .tokenHash(TOKEN_HASH_2)
            .tokenPrefix("ppl_22222222")
            .invoiceSnapshotHash(SNAPSHOT_HASH_1)
            .expiresAt(expiresAt)
            .build();

        assertThatThrownBy(() -> paymentLinkRepository.saveAndFlush(competingActive))
            .isInstanceOf(DataIntegrityViolationException.class);

        entityManager.clear();

        PaymentLink toRevoke = paymentLinkRepository.findById(first.getId()).orElseThrow();
        LocalDateTime revokedAt = LocalDateTime.of(2026, 11, 12, 9, 30);
        toRevoke.revoke(revokedAt, "ROTATED_BY_SELLER");
        paymentLinkRepository.saveAndFlush(toRevoke);

        assertThat(toRevoke.getActiveInvoiceId()).isNull();
        assertThat(toRevoke.isRevoked()).isTrue();
        assertThat(paymentLinkRepository.findByActiveInvoiceId(approvedInvoice.getId())).isEmpty();

        PaymentLink replacement = paymentLinkRepository.saveAndFlush(PaymentLink.builder()
            .invoice(approvedInvoice)
            .user(seller)
            .tokenHash(TOKEN_HASH_2)
            .tokenPrefix("ppl_22222222")
            .invoiceSnapshotHash(SNAPSHOT_HASH_1)
            .expiresAt(expiresAt.plusDays(7))
            .build());

        assertThat(replacement.getActiveInvoiceId()).isEqualTo(approvedInvoice.getId());
        assertThat(paymentLinkRepository.findByActiveInvoiceId(approvedInvoice.getId()))
            .map(PaymentLink::getId)
            .contains(replacement.getId());

        List<PaymentLink> history = paymentLinkRepository.findByInvoiceIdOrderByCreatedAtDesc(approvedInvoice.getId());
        assertThat(history).hasSize(2);
    }

    @Test
    void tracksAccessAndEvaluatesUsabilityWindowAndValidation() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 12, 0);
        PaymentLink link = paymentLinkRepository.saveAndFlush(PaymentLink.builder()
            .invoice(approvedInvoice)
            .user(seller)
            .tokenHash(TOKEN_HASH_1)
            .tokenPrefix("ppl_11111111")
            .invoiceSnapshotHash(SNAPSHOT_HASH_1)
            .expiresAt(now.plusDays(3))
            .build());

        assertThat(link.isUsableAt(now)).isTrue();
        assertThat(link.isExpired(now)).isFalse();
        assertThat(link.isExpired(now.plusDays(3))).isTrue();
        assertThat(link.isUsableAt(now.plusDays(4))).isFalse();

        link.recordAccess(now.plusMinutes(5));
        paymentLinkRepository.saveAndFlush(link);
        entityManager.clear();

        PaymentLink reloaded = paymentLinkRepository.findByTokenHashForUpdate(TOKEN_HASH_1).orElseThrow();
        assertThat(reloaded.getAccessCount()).isEqualTo(1);
        assertThat(reloaded.getLastAccessedAt()).isEqualTo(now.plusMinutes(5));
        assertThat(paymentLinkRepository.findByUserIdOrderByCreatedAtDesc(seller.getId())).hasSize(1);

        PaymentLink invalidHash = PaymentLink.builder()
            .invoice(approvedInvoice)
            .user(seller)
            .tokenHash("not-a-valid-sha256-hex")
            .tokenPrefix("bad")
            .invoiceSnapshotHash(SNAPSHOT_HASH_1)
            .expiresAt(now.plusDays(1))
            .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(invalidHash))
            .isInstanceOf(ConstraintViolationException.class);
    }
}

