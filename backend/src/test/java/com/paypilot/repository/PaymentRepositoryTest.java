package com.paypilot.repository;

import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentStatus;
import com.paypilot.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        User user = userRepository.saveAndFlush(User.builder()
            .email("merchant@paypilot.test")
            .name("Merchant")
            .passwordHash("hashed")
            .build());

        invoice = invoiceRepository.saveAndFlush(Invoice.builder()
            .user(user)
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
}

