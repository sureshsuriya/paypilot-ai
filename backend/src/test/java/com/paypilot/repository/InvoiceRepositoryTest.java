package com.paypilot.repository;

import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class InvoiceRepositoryTest {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(User.builder()
            .email("owner@paypilot.test")
            .name("Owner User")
            .passwordHash("hashed")
            .build());
    }

    @Test
    void persistsInvoiceWithBigDecimalPrecisionAndDefaults() {
        Invoice invoice = Invoice.builder()
            .user(owner)
            .invoiceNumber("INV-2026-001")
            .clientName("Acme Studio")
            .clientEmail("billing@acme.test")
            .description("Design and API consulting services")
            .amount(new BigDecimal("1250.75"))
            .currency("USD")
            .dueDate(LocalDate.of(2026, 12, 15))
            .issuedAt(LocalDate.of(2026, 11, 10))
            .aiExtracted(true)
            .aiConfidence(new BigDecimal("0.9450"))
            .build();

        Invoice saved = invoiceRepository.saveAndFlush(invoice);
        entityManager.clear();

        Invoice reloaded = invoiceRepository.findByIdAndUserId(saved.getId(), owner.getId()).orElseThrow();
        assertThat(reloaded.getAmount()).isEqualByComparingTo("1250.75");
        assertThat(reloaded.getAiConfidence()).isEqualByComparingTo("0.9450");
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(reloaded.getAiExtracted()).isTrue();
        assertThat(reloaded.getCurrency()).isEqualTo("USD");
    }

    @Test
    void enforcesUniqueInvoiceNumberPerUser() {
        invoiceRepository.saveAndFlush(Invoice.builder()
            .user(owner)
            .invoiceNumber("INV-100")
            .clientName("Client A")
            .clientEmail("a@client.test")
            .amount(new BigDecimal("100.00"))
            .build());

        Invoice duplicate = Invoice.builder()
            .user(owner)
            .invoiceNumber("INV-100")
            .clientName("Client B")
            .clientEmail("b@client.test")
            .amount(new BigDecimal("200.00"))
            .build();

        assertThatThrownBy(() -> invoiceRepository.saveAndFlush(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameInvoiceNumberForDifferentUsers() {
        User otherUser = userRepository.saveAndFlush(User.builder()
            .email("other@paypilot.test")
            .name("Other User")
            .passwordHash("hashed2")
            .build());

        invoiceRepository.saveAndFlush(Invoice.builder()
            .user(owner)
            .invoiceNumber("INV-001")
            .clientName("Client One")
            .clientEmail("c1@test.com")
            .amount(new BigDecimal("50.00"))
            .build());

        Invoice second = invoiceRepository.saveAndFlush(Invoice.builder()
            .user(otherUser)
            .invoiceNumber("INV-001")
            .clientName("Client Two")
            .clientEmail("c2@test.com")
            .amount(new BigDecimal("75.00"))
            .build());

        assertThat(second.getId()).isNotNull();
    }

    @Test
    void filtersByUserAndStatus() {
        invoiceRepository.saveAndFlush(Invoice.builder()
            .user(owner)
            .invoiceNumber("INV-DRAFT")
            .clientName("Draft Co")
            .clientEmail("draft@co.test")
            .amount(new BigDecimal("300.00"))
            .status(InvoiceStatus.DRAFT)
            .build());

        invoiceRepository.saveAndFlush(Invoice.builder()
            .user(owner)
            .invoiceNumber("INV-SENT")
            .clientName("Sent Co")
            .clientEmail("sent@co.test")
            .amount(new BigDecimal("450.00"))
            .status(InvoiceStatus.SENT)
            .paypalInvoiceId("INV2-XXXX-YYYY")
            .build());

        List<Invoice> sentInvoices = invoiceRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
            owner.getId(), InvoiceStatus.SENT);

        assertThat(sentInvoices).hasSize(1);
        assertThat(sentInvoices.get(0).getInvoiceNumber()).isEqualTo("INV-SENT");
        assertThat(invoiceRepository.findByPaypalInvoiceId("INV2-XXXX-YYYY")).isPresent();
    }

    @Test
    void rejectsZeroOrNegativeAmount() {
        Invoice zeroAmount = Invoice.builder()
            .user(owner)
            .invoiceNumber("INV-ZERO")
            .clientName("Zero Co")
            .clientEmail("zero@co.test")
            .amount(BigDecimal.ZERO)
            .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(zeroAmount))
            .isInstanceOf(ConstraintViolationException.class);
    }
}

