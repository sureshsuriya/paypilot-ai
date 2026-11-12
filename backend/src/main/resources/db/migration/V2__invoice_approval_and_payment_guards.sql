-- =============================================================================
-- PayPilot AI — Flyway Migration V2: Approval Workflow & Payment Guards
-- Database: paypilot_ai | MySQL 8.0.45
-- =============================================================================
-- Additive migration only. V1__init_schema.sql remains completely unchanged.
-- =============================================================================

-- -------------------------------------------------------------------------
-- 1. Invoice explicit approval metadata & snapshot hash
-- -------------------------------------------------------------------------
ALTER TABLE invoices ADD COLUMN approved_at DATETIME NULL;
ALTER TABLE invoices ADD COLUMN approved_by_user_id BIGINT NULL;
ALTER TABLE invoices ADD COLUMN approved_snapshot_hash VARCHAR(64) NULL;
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_approved_by FOREIGN KEY (approved_by_user_id) REFERENCES users (id);
CREATE INDEX idx_invoices_approved_by ON invoices (approved_by_user_id);

-- -------------------------------------------------------------------------
-- 2. Payment snapshot binding, idempotency, and duplicate order/capture guards
-- -------------------------------------------------------------------------
ALTER TABLE payments ADD COLUMN invoice_snapshot_hash VARCHAR(64) NULL;
ALTER TABLE payments ADD COLUMN idempotency_key VARCHAR(100) NULL;
ALTER TABLE payments ADD COLUMN paypal_approval_url VARCHAR(1000) NULL;
CREATE UNIQUE INDEX uq_payments_paypal_order_id ON payments (paypal_order_id);
CREATE UNIQUE INDEX uq_payments_paypal_capture_id ON payments (paypal_capture_id);
CREATE INDEX idx_payments_invoice_status ON payments (invoice_id, status);

