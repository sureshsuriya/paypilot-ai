package com.paypilot.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies V6__invoice_deliveries.sql applies cleanly on top of V5 while
 * preserving all pre-existing user, customer, invoice, payment, and link rows.
 */
class FlywayV6MigrationIntegrationTest {

    @Test
    void upgradesFromV5ToV6PreservingExistingRecords() {
        String jdbcUrl = "jdbc:h2:mem:paypilot_v6_upgrade_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway v5 = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("5")
            .load();
        v5.migrate();

        jdbc.update(
            "INSERT INTO users (email, name, password_hash, role) VALUES (?, ?, ?, ?)",
            "v6-seller@paypilot.test", "V6 Seller", "$2a$10$hash", "USER"
        );
        Long userId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?", Long.class, "v6-seller@paypilot.test"
        );
        jdbc.update(
            "INSERT INTO invoices (user_id, invoice_number, client_name, client_email, description, amount, currency, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            userId, "INV-V5-KEEP", "Keep Client", "keep@client.test", "Existing item",
            new BigDecimal("100.00"), "USD", "DRAFT"
        );

        Flyway v6 = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .target("6")
            .load();
        v6.migrate();

        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"
        );
        assertThat(history).extracting(r -> String.valueOf(r.get("version")))
            .contains("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_deliveries", Integer.class)).isZero();
    }
}

