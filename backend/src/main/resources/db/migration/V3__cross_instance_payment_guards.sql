-- =============================================================================
-- PayPilot AI — Flyway Migration V3: Cross-Instance Leases, Order Intents & Guards
-- Database: paypilot_ai | MySQL 8.0.45
-- =============================================================================
-- Additive migration only. V1 and V2 remain completely unchanged.
-- =============================================================================

-- -------------------------------------------------------------------------
-- 1. Cross-instance atomic invoice operation lease metadata
-- -------------------------------------------------------------------------
ALTER TABLE invoices ADD COLUMN payment_lock_owner VARCHAR(64) NULL;
ALTER TABLE invoices ADD COLUMN payment_lock_until DATETIME NULL;
CREATE INDEX idx_invoices_payment_lock ON invoices (id, payment_lock_until);

-- -------------------------------------------------------------------------
-- 2. Durable PayPal order-creation intents (preserves V1 paypal_order_id NOT NULL)
-- -------------------------------------------------------------------------
CREATE TABLE payment_order_intents (
    id                    BIGINT         NOT NULL AUTO_INCREMENT,
    invoice_id            BIGINT         NOT NULL,
    invoice_snapshot_hash VARCHAR(64)    NOT NULL,
    idempotency_key       VARCHAR(100)   NOT NULL,
    attempt_number        INT            NOT NULL,
    amount                DECIMAL(15, 2) NOT NULL,
    currency              VARCHAR(10)    NOT NULL,
    status                VARCHAR(30)    NOT NULL,
    active_invoice_id     BIGINT         NULL,
    paypal_order_id       VARCHAR(255)   NULL,
    created_at            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_poi_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id)
);

CREATE UNIQUE INDEX uq_poi_idempotency_key ON payment_order_intents (idempotency_key);
CREATE UNIQUE INDEX uq_poi_active_invoice ON payment_order_intents (active_invoice_id);
CREATE INDEX idx_poi_invoice_snapshot ON payment_order_intents (invoice_id, invoice_snapshot_hash);

-- -------------------------------------------------------------------------
-- 3. Active invoice payment slot guard & durable capture recovery metadata
-- -------------------------------------------------------------------------
ALTER TABLE payments ADD COLUMN active_invoice_id BIGINT NULL;
ALTER TABLE payments ADD COLUMN capture_idempotency_key VARCHAR(100) NULL;
ALTER TABLE payments ADD COLUMN capture_requested_at DATETIME NULL;

-- Backfill existing active/captured payments; CANCELLED and FAILED keep active_invoice_id = NULL
UPDATE payments
SET active_invoice_id = invoice_id
WHERE status IN ('CREATED', 'APPROVED', 'PENDING', 'CAPTURED');

CREATE UNIQUE INDEX uq_payments_active_invoice ON payments (active_invoice_id);
CREATE UNIQUE INDEX uq_payments_idempotency_key ON payments (idempotency_key);
CREATE UNIQUE INDEX uq_payments_capture_idempotency_key ON payments (capture_idempotency_key);

