-- =============================================================================
-- PayPilot AI — Flyway Migration V5: Seller Customers & Invoice Contact Fields
-- Database: paypilot_ai | MySQL 8.0.45
-- =============================================================================
-- Additive migration only. V1, V2, V3, and V4 remain completely unchanged.
-- Stores seller-scoped customer records (name, email, optional WhatsApp phone,
-- company, notes) and links invoices optionally via nullable customer_id &
-- client_phone so all existing invoices stay completely preserved.
-- =============================================================================

CREATE TABLE IF NOT EXISTS customers (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    user_id     BIGINT          NOT NULL,
    name        VARCHAR(255)    NOT NULL,
    email       VARCHAR(255)    NOT NULL,
    phone       VARCHAR(32)     NULL,
    company     VARCHAR(255)    NULL,
    notes       VARCHAR(1000)   NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_customers_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX uq_customers_user_email ON customers (user_id, email);
CREATE INDEX idx_customers_user_id ON customers (user_id);

ALTER TABLE invoices ADD COLUMN customer_id BIGINT NULL;
ALTER TABLE invoices ADD COLUMN client_phone VARCHAR(32) NULL;
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_customer FOREIGN KEY (customer_id) REFERENCES customers (id);
CREATE INDEX idx_invoices_customer_id ON invoices (customer_id);
