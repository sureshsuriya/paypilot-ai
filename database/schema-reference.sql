-- =============================================================================
-- PayPilot AI — Schema Reference (Human-Readable)
-- Database: paypilot_ai | MySQL 8.0.45
--
-- ⚠️  IMPORTANT: This file is for REFERENCE ONLY.
--     Do NOT run this file manually. Flyway manages migrations.
--     The authoritative schema is in backend/src/main/resources/db/migration/
-- =============================================================================

-- Table 1: users
-- Minimal auth — email + hashed password only
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL DEFAULT 'USER',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Table 2: invoices
-- Core invoice records. Status PAID only on verified PayPal capture.
CREATE TABLE IF NOT EXISTS invoices (
    id                BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT         NOT NULL REFERENCES users(id),
    paypal_invoice_id VARCHAR(255),
    invoice_number    VARCHAR(100)   NOT NULL,
    client_name       VARCHAR(255)   NOT NULL,
    client_email      VARCHAR(255)   NOT NULL,
    description       TEXT,
    amount            DECIMAL(15,2)  NOT NULL,
    currency          VARCHAR(10)    NOT NULL DEFAULT 'USD',
    status            VARCHAR(50)    NOT NULL DEFAULT 'DRAFT',
    -- DRAFT | SENT | PAID | OVERDUE | CANCELLED
    -- PAID requires payments.status = CAPTURED with paypal_capture_id populated
    due_date          DATE,
    issued_at         DATE,
    paid_at           DATETIME,
    ai_extracted      BOOLEAN        NOT NULL DEFAULT FALSE,
    ai_confidence     DECIMAL(5,4),
    created_at        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Table 3: payments
-- PayPal payment verification records. Never stores card/bank details.
-- paypal_response_summary: minimal verification fields only.
CREATE TABLE IF NOT EXISTS payments (
    id                     BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id             BIGINT        NOT NULL REFERENCES invoices(id),
    paypal_order_id        VARCHAR(255)  NOT NULL,
    paypal_capture_id      VARCHAR(255),  -- populated on CAPTURED
    amount                 DECIMAL(15,2) NOT NULL,
    currency               VARCHAR(10)   NOT NULL DEFAULT 'USD',
    status                 VARCHAR(50)   NOT NULL DEFAULT 'CREATED',
    -- CREATED | APPROVED | CAPTURED | FAILED | CANCELLED
    paypal_response_summary JSON,
    -- Stores: order_id, capture_id, status, amount, currency ONLY
    captured_at            DATETIME,
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Table 4: audit_logs
-- Immutable financial event log. Append-only. Never update or delete rows.
CREATE TABLE IF NOT EXISTS audit_logs (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT,      -- NULL for system events
    entity_type VARCHAR(100) NOT NULL,  -- INVOICE | PAYMENT | USER
    entity_id   BIGINT,
    action      VARCHAR(100) NOT NULL,  -- INVOICE_CREATED | PAYMENT_CAPTURED | etc.
    actor       VARCHAR(50)  NOT NULL,  -- USER | AI_AGENT | SYSTEM
    details     JSON,                   -- non-sensitive context only
    ip_address  VARCHAR(45),
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Table 5: ai_extraction_logs
-- AI transparency record for every extraction operation.
CREATE TABLE IF NOT EXISTS ai_extraction_logs (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id        BIGINT       REFERENCES invoices(id),
    model_used        VARCHAR(100) NOT NULL,  -- e.g. gpt-4o
    prompt_tokens     INT,
    completion_tokens INT,
    confidence_score  DECIMAL(5,4),
    validated_by_user BOOLEAN      NOT NULL DEFAULT FALSE,
    extraction_method VARCHAR(50)  NOT NULL DEFAULT 'TEXT',  -- TEXT | OCR
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

