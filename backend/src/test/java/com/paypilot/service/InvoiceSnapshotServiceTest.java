package com.paypilot.service;

import com.paypilot.model.Invoice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceSnapshotServiceTest {

    private final InvoiceSnapshotService snapshotService = new InvoiceSnapshotService();

    @Test
    void normalizesAmountScaleAndCaseWhileDetectingEveryMaterialFieldChange() {
        LocalDate due = LocalDate.of(2026, 12, 1);

        Invoice base = Invoice.builder()
            .id(10L)
            .invoiceNumber("INV-20261111-ABC123")
            .clientName("Alex Morgan")
            .clientEmail("Alex@Example.com")
            .description("Website development")
            .amount(new BigDecimal("750"))
            .currency("usd")
            .dueDate(due)
            .build();

        Invoice equivalent = Invoice.builder()
            .id(10L)
            .invoiceNumber("INV-20261111-ABC123")
            .clientName("  Alex Morgan ")
            .clientEmail("alex@example.com")
            .description("Website development")
            .amount(new BigDecimal("750.00"))
            .currency("USD")
            .dueDate(due)
            .build();

        String baseHash = snapshotService.computeHash(base);
        assertThat(baseHash).hasSize(64);
        assertThat(snapshotService.computeHash(equivalent)).isEqualTo(baseHash);
        assertThat(snapshotService.hashesMatch(baseHash, baseHash)).isTrue();

        Invoice changedAmount = cloneWith(base, builder -> builder.amount(new BigDecimal("750.01")));
        Invoice changedClient = cloneWith(base, builder -> builder.clientName("Jordan"));
        Invoice changedEmail = cloneWith(base, builder -> builder.clientEmail("other@example.com"));
        Invoice changedDesc = cloneWith(base, builder -> builder.description("Website development + SEO"));
        Invoice changedCurrency = cloneWith(base, builder -> builder.currency("EUR"));
        Invoice changedDueDate = cloneWith(base, builder -> builder.dueDate(due.plusDays(1)));
        Invoice changedNumber = cloneWith(base, builder -> builder.invoiceNumber("INV-20261111-XYZ999"));

        assertThat(snapshotService.computeHash(changedAmount)).isNotEqualTo(baseHash);
        assertThat(snapshotService.computeHash(changedClient)).isNotEqualTo(baseHash);
        assertThat(snapshotService.computeHash(changedEmail)).isNotEqualTo(baseHash);
        assertThat(snapshotService.computeHash(changedDesc)).isNotEqualTo(baseHash);
        assertThat(snapshotService.computeHash(changedCurrency)).isNotEqualTo(baseHash);
        assertThat(snapshotService.computeHash(changedDueDate)).isNotEqualTo(baseHash);
        assertThat(snapshotService.computeHash(changedNumber)).isNotEqualTo(baseHash);
    }

    private Invoice cloneWith(
            Invoice source,
            java.util.function.Consumer<Invoice.InvoiceBuilder> mutator) {
        Invoice.InvoiceBuilder builder = Invoice.builder()
            .id(source.getId())
            .invoiceNumber(source.getInvoiceNumber())
            .clientName(source.getClientName())
            .clientEmail(source.getClientEmail())
            .description(source.getDescription())
            .amount(source.getAmount())
            .currency(source.getCurrency())
            .dueDate(source.getDueDate());
        mutator.accept(builder);
        return builder.build();
    }
}

