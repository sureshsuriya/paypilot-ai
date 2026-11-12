-- =============================================================================
-- PayPilot AI — Flyway Migration V6: Invoice Delivery Audit Logs
-- Database: paypilot_ai | MySQL 8.0.45
-- =============================================================================
-- Additive migration only. V1..V5 remain untouched.
-- Records sanitized invoice delivery attempts (Email) without ever storing
-- raw secret payment-link tokens or SMTP credentials.
-- =============================================================================

CREATE TABLE IF NOT EXISTS invoice_deliveries (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    invoice_id      BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    payment_link_id BIGINT          NULL,
    channel         VARCHAR(20)     NOT NULL,
    recipient       VARCHAR(255)    NOT NULL,
    subject         VARCHAR(255)    NULL,
    status          VARCHAR(20)     NOT NULL,
    failure_reason  VARCHAR(500)    NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_deliveries_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id),
    CONSTRAINT fk_deliveries_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_deliveries_payment_link FOREIGN KEY (payment_link_id) REFERENCES payment_links (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_deliveries_invoice_id ON invoice_deliveries (invoice_id);
CREATE INDEX idx_deliveries_user_id ON invoice_deliveries (user_id);
CREATE INDEX idx_deliveries_cooldown ON invoice_deliveries (invoice_id, channel, status, created_at);

