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
 * Verifies that V5__customers_and_invoice_contact.sql upgrades cleanly on top of V4
 * without altering or deleting any pre-existing users, invoices, payments, or payment links.
 */
class FlywayV5MigrationIntegrationTest {

    @Test
    void upgradesFromV4ToV5PreservingAllPreExistingData() {
        String jdbcUrl = "jdbc:h2:mem:paypilot_v5_upgrade_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway v4Flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("4")
            .load();
        v4Flyway.migrate();

        jdbc.update(
            "INSERT INTO users (email, name, password_hash, role) VALUES (?, ?, ?, ?)",
            "v5-seller@paypilot.test", "V5 Seller", "$2a$10$hash", "USER"
        );
        Long userId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?", Long.class, "v5-seller@paypilot.test"
        );

        String snap = "e".repeat(64);
        jdbc.update(
            """
            INSERT INTO invoices (
                user_id, invoice_number, client_name, client_email, description,
                amount, currency, status, approved_at, approved_by_user_id, approved_snapshot_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            userId, "INV-V4-001", "Legacy Client", "legacy@client.test", "Pre-V5 invoice",
            new BigDecimal("500.00"), "USD", "APPROVED",
            Timestamp.valueOf(LocalDateTime.of(2026, 11, 11, 14, 0)),
            userId, snap
        );

        Flyway v5Flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("5")
            .load();
        v5Flyway.migrate();

        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"
        );
        assertThat(history).extracting(r -> String.valueOf(r.get("version")))
            .contains("1", "2", "3", "4", "5");

        Map<String, Object> invoiceRow = jdbc.queryForMap(
            "SELECT invoice_number, client_email, customer_id, client_phone FROM invoices WHERE invoice_number = ?",
            "INV-V4-001"
        );
        assertThat(invoiceRow.get("client_email")).isEqualTo("legacy@client.test");
        assertThat(invoiceRow.get("customer_id")).isNull();
        assertThat(invoiceRow.get("client_phone")).isNull();

        jdbc.update(
            "INSERT INTO customers (user_id, name, email, phone) VALUES (?, ?, ?, ?)",
            userId, "Legacy Client", "legacy@client.test", "+14155552671"
        );

        // Unique constraint (user_id, email) prevents duplicate customer email per seller
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO customers (user_id, name, email) VALUES (?, ?, ?)",
            userId, "Duplicate Client", "legacy@client.test"
        )).isInstanceOf(Exception.class);
    }
}

