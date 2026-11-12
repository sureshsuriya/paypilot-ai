package com.paypilot.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that V4__payment_links.sql is cleanly additive when applied on top
 * of a database already containing V1, V2, and V3 records, without altering or
 * deleting any existing user, invoice, or payment data.
 */
class FlywayV4MigrationIntegrationTest {

    @Test
    void upgradesFromV3ToV4PreservingExistingUsersInvoicesAndPayments() {
        String jdbcUrl = "jdbc:h2:mem:paypilot_v4_upgrade_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Apply V1..V3 first to simulate the pre-Phase-1 live database state
        Flyway v3Flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("3")
            .load();
        v3Flyway.migrate();

        // Populate pre-existing V3 seller, PAID invoice, DRAFT invoice, and CAPTURED payment
        jdbc.update(
            "INSERT INTO users (email, name, password_hash, role) VALUES (?, ?, ?, ?)",
            "existing-seller@paypilot.test", "Existing Seller", "$2a$10$hash", "USER"
        );
        Long userId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?", Long.class, "existing-seller@paypilot.test"
        );

        String paidSnapshot = "d".repeat(64);
        jdbc.update(
            """
            INSERT INTO invoices (
                user_id, invoice_number, client_name, client_email, description,
                amount, currency, status, approved_at, approved_by_user_id, approved_snapshot_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            userId, "INV-PRE-PAID", "Pre Client", "pre@client.test", "Completed sandbox project",
            new BigDecimal("750.00"), "USD", "PAID",
            Timestamp.valueOf(LocalDateTime.of(2026, 11, 11, 11, 0)),
            userId, paidSnapshot
        );
        Long paidInvoiceId = jdbc.queryForObject(
            "SELECT id FROM invoices WHERE invoice_number = ?", Long.class, "INV-PRE-PAID"
        );

        jdbc.update(
            """
            INSERT INTO invoices (
                user_id, invoice_number, client_name, client_email, description,
                amount, currency, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            userId, "INV-PRE-DRAFT", "Draft Client", "draft@client.test", "Pending draft",
            new BigDecimal("250.00"), "USD", "DRAFT"
        );

        jdbc.update(
            """
            INSERT INTO payments (
                invoice_id, paypal_order_id, paypal_capture_id, amount, currency,
                status, active_invoice_id, invoice_snapshot_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            paidInvoiceId, "ORDER-PRE-750", "CAPTURE-PRE-750",
            new BigDecimal("750.00"), "USD", "CAPTURED", paidInvoiceId, paidSnapshot
        );

        // Apply V4 on top of populated V3 database
        Flyway v4Flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("4")
            .load();
        v4Flyway.migrate();

        // Confirm V1..V4 all migrated cleanly
        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"
        );
        assertThat(history).hasSize(4);
        assertThat(history).extracting(row -> String.valueOf(row.get("version")))
            .containsExactly("1", "2", "3", "4");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(Boolean.TRUE));

        // Confirm all pre-existing data survived untouched and payment_link_id defaults to NULL
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class)).isEqualTo(2);

        Map<String, Object> paymentRow = jdbc.queryForMap(
            "SELECT paypal_order_id, paypal_capture_id, status, amount, payment_link_id FROM payments WHERE invoice_id = ?",
            paidInvoiceId
        );
        assertThat(paymentRow.get("paypal_order_id")).isEqualTo("ORDER-PRE-750");
        assertThat(paymentRow.get("paypal_capture_id")).isEqualTo("CAPTURE-PRE-750");
        assertThat(paymentRow.get("status")).isEqualTo("CAPTURED");
        assertThat((BigDecimal) paymentRow.get("amount")).isEqualByComparingTo("750.00");
        assertThat(paymentRow.get("payment_link_id")).isNull();

        // Confirm payment_links table exists and enforces uq_payment_links_active_invoice
        LocalDateTime expiresAt = LocalDateTime.of(2026, 11, 30, 23, 59);
        jdbc.update(
            """
            INSERT INTO payment_links (
                invoice_id, user_id, active_invoice_id, token_hash, token_prefix,
                invoice_snapshot_hash, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            paidInvoiceId, userId, paidInvoiceId, "f".repeat(64), "ppl_ffff", paidSnapshot,
            Timestamp.valueOf(expiresAt)
        );

        assertThatThrownBy(() -> jdbc.update(
            """
            INSERT INTO payment_links (
                invoice_id, user_id, active_invoice_id, token_hash, token_prefix,
                invoice_snapshot_hash, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            paidInvoiceId, userId, paidInvoiceId, "e".repeat(64), "ppl_eeee", paidSnapshot,
            Timestamp.valueOf(expiresAt)
        )).isInstanceOf(Exception.class);
    }
}

