package com.paypilot.repository;

import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentOrderIntent;
import com.paypilot.model.PaymentOrderIntentStatus;
import com.paypilot.model.PaymentStatus;
import com.paypilot.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentOrderIntentRepository paymentOrderIntentRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User owner;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(User.builder()
            .email("merchant@paypilot.test")
            .name("Merchant")
            .passwordHash("hashed")
            .build());

        invoice = invoiceRepository.saveAndFlush(Invoice.builder()
            .user(owner)
            .invoiceNumber("INV-PAY-001")
            .clientName("Buyer Corp")
            .clientEmail("buyer@corp.test")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(InvoiceStatus.SENT)
            .build());
    }

    @Test
    void savesCapturedPaymentWithJsonSummaryAndBigDecimalAmount() {
        LocalDateTime capturedTime = LocalDateTime.of(2026, 11, 10, 21, 0, 0);

        Payment payment = Payment.builder()
            .invoice(invoice)
            .paypalOrderId("5O190127TN364715T")
            .paypalCaptureId("2GG279541U471931P")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.CAPTURED)
            .paypalResponseSummary(Map.of(
                "orderId", "5O190127TN364715T",
                "captureId", "2GG279541U471931P",
                "status", "COMPLETED",
                "amount", "499.99",
                "currency", "USD",
                "sandbox", true
            ))
            .capturedAt(capturedTime)
            .build();

        Payment saved = paymentRepository.saveAndFlush(payment);
        entityManager.clear();

        Payment reloaded = paymentRepository.findByPaypalOrderId("5O190127TN364715T").orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getPaypalCaptureId()).isEqualTo("2GG279541U471931P");
        assertThat(reloaded.getAmount()).isEqualByComparingTo("499.99");
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(reloaded.getPaypalResponseSummary())
            .containsEntry("status", "COMPLETED")
            .containsEntry("sandbox", true);
        assertThat(paymentRepository.existsByInvoiceIdAndStatus(invoice.getId(), PaymentStatus.CAPTURED)).isTrue();
        assertThat(paymentRepository.findByPaypalCaptureId("2GG279541U471931P")).isPresent();
    }

    @Test
    void enforcesUniqueOrderAndCaptureIdsWhileAllowingMultipleNullCaptureIds() {
        Payment cancelled = paymentRepository.saveAndFlush(Payment.builder()
            .invoice(invoice)
            .paypalOrderId("ORDER-CANCELLED-1")
            .paypalCaptureId(null)
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.CANCELLED)
            .invoiceSnapshotHash("b".repeat(64))
            .idempotencyKey("idem-1")
            .build());

        Payment pending = paymentRepository.saveAndFlush(Payment.builder()
            .invoice(invoice)
            .paypalOrderId("ORDER-PENDING-2")
            .paypalCaptureId(null)
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.PENDING)
            .invoiceSnapshotHash("b".repeat(64))
            .idempotencyKey("idem-2")
            .paypalApprovalUrl("https://www.sandbox.paypal.com/checkoutnow?token=ORDER-PENDING-2")
            .build());

        assertThat(cancelled.getId()).isNotNull();
        assertThat(pending.getId()).isNotNull();
        assertThat(paymentRepository.findByInvoiceIdAndStatusIn(
            invoice.getId(), List.of(PaymentStatus.CREATED, PaymentStatus.PENDING))).hasSize(1);
        assertThat(paymentRepository.findByIdempotencyKey("idem-2")).isPresent();

        Payment duplicateOrder = Payment.builder()
            .invoice(invoice)
            .paypalOrderId("ORDER-PENDING-2")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.CREATED)
            .build();

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(duplicateOrder))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueNonNullCaptureId() {
        Invoice secondInvoice = invoiceRepository.saveAndFlush(Invoice.builder()
            .user(owner)
            .invoiceNumber("INV-PAY-002")
            .clientName("Buyer Two")
            .clientEmail("buyer2@corp.test")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(InvoiceStatus.APPROVED)
            .build());

        paymentRepository.saveAndFlush(Payment.builder()
            .invoice(invoice)
            .paypalOrderId("ORDER-CAP-1")
            .paypalCaptureId("CAP-UNIQUE-99")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.CAPTURED)
            .build());

        Payment duplicateCapture = Payment.builder()
            .invoice(secondInvoice)
            .paypalOrderId("ORDER-CAP-2")
            .paypalCaptureId("CAP-UNIQUE-99")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.CAPTURED)
            .build();

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(duplicateCapture))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesActiveInvoiceSlotAndIdempotencyConstraintsAndReleasesOnCancelOrFail() {
        Payment active = paymentRepository.saveAndFlush(Payment.builder()
            .invoice(invoice)
            .paypalOrderId("ORDER-SLOT-1")
            .idempotencyKey("ord-slot-key-1")
            .captureIdempotencyKey("cap-slot-key-1")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.CREATED)
            .build());
        assertThat(active.getActiveInvoiceId()).isEqualTo(invoice.getId());

        // Second active payment for same invoice blocked by uq_payments_active_invoice
        Payment secondActive = Payment.builder()
            .invoice(invoice)
            .paypalOrderId("ORDER-SLOT-2")
            .idempotencyKey("ord-slot-key-2")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.CREATED)
            .build();
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(secondActive))
            .isInstanceOf(DataIntegrityViolationException.class);

        entityManager.clear();

        // Cancelling releases active_invoice_id -> another order for same invoice succeeds
        Payment reloaded = paymentRepository.findByPaypalOrderId("ORDER-SLOT-1").orElseThrow();
        reloaded.setStatus(PaymentStatus.CANCELLED);
        paymentRepository.saveAndFlush(reloaded);
        assertThat(reloaded.getActiveInvoiceId()).isNull();

        Payment replacement = paymentRepository.saveAndFlush(Payment.builder()
            .invoice(invoice)
            .paypalOrderId("ORDER-SLOT-3")
            .idempotencyKey("ord-slot-key-3")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.FAILED)
            .build());
        assertThat(replacement.getActiveInvoiceId()).isNull();

        // Duplicate idempotency_key blocked even after FAILED
        Payment duplicateKey = Payment.builder()
            .invoice(invoice)
            .paypalOrderId("ORDER-SLOT-4")
            .idempotencyKey("ord-slot-key-3")
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentStatus.CREATED)
            .build();
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(duplicateKey))
            .isInstanceOf(DataIntegrityViolationException.class);

        entityManager.clear();

        // Intent active slot allows at most one PENDING intent per invoice
        PaymentOrderIntent intent1 = paymentOrderIntentRepository.saveAndFlush(PaymentOrderIntent.builder()
            .invoice(invoice)
            .invoiceSnapshotHash("a".repeat(64))
            .idempotencyKey("intent-key-1")
            .attemptNumber(0)
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentOrderIntentStatus.PENDING)
            .build());
        assertThat(intent1.getActiveInvoiceId()).isEqualTo(invoice.getId());

        PaymentOrderIntent duplicateIntent = PaymentOrderIntent.builder()
            .invoice(invoice)
            .invoiceSnapshotHash("a".repeat(64))
            .idempotencyKey("intent-key-2")
            .attemptNumber(1)
            .amount(new BigDecimal("499.99"))
            .currency("USD")
            .status(PaymentOrderIntentStatus.PENDING)
            .build();
        assertThatThrownBy(() -> paymentOrderIntentRepository.saveAndFlush(duplicateIntent))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}

