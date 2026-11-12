-- =============================================================================
-- PayPilot AI — Flyway Migration V4: Secure Customer Payment Links
-- Database: paypilot_ai | MySQL 8.0.45
-- =============================================================================
-- Additive migration only. V1, V2, and V3 remain completely unchanged.
-- Stores ONLY SHA-256 hashes of payment link tokens (never raw tokens).
-- active_invoice_id enforces at most one active (non-revoked) payment link per
-- invoice while retaining historical revoked/expired links for auditing.
-- =============================================================================

CREATE TABLE IF NOT EXISTS payment_links (
    id                    BIGINT          NOT NULL AUTO_INCREMENT,
    invoice_id            BIGINT          NOT NULL,
    user_id               BIGINT          NOT NULL,
    active_invoice_id     BIGINT          NULL,
    token_hash            VARCHAR(64)     NOT NULL,
    token_prefix          VARCHAR(16)     NOT NULL,
    invoice_snapshot_hash VARCHAR(64)     NOT NULL,
    expires_at            DATETIME        NOT NULL,
    revoked_at            DATETIME        NULL,
    revocation_reason     VARCHAR(100)    NULL,
    access_count          INT             NOT NULL DEFAULT 0,
    last_accessed_at      DATETIME        NULL,
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_links_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id),
    CONSTRAINT fk_payment_links_user    FOREIGN KEY (user_id)    REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX uq_payment_links_token_hash     ON payment_links (token_hash);
CREATE UNIQUE INDEX uq_payment_links_active_invoice ON payment_links (active_invoice_id);
CREATE INDEX idx_payment_links_invoice_id           ON payment_links (invoice_id);
CREATE INDEX idx_payment_links_user_id              ON payment_links (user_id);
CREATE INDEX idx_payment_links_expires_at           ON payment_links (expires_at);

-- Optional nullable provenance link on payments: existing and seller-direct
-- payments remain NULL and completely unaffected.
ALTER TABLE payments ADD COLUMN payment_link_id BIGINT NULL;
ALTER TABLE payments ADD CONSTRAINT fk_payments_payment_link FOREIGN KEY (payment_link_id) REFERENCES payment_links (id);
CREATE INDEX idx_payments_payment_link_id ON payments (payment_link_id);

