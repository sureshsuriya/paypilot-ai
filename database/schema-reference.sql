-- =============================================================================
-- PayPilot AI — Schema Reference (Human-Readable, Flyway V1 through V6)
-- Database: paypilot_ai | MySQL 8.0.45
--
-- IMPORTANT: Reference only. Do NOT run manually.
-- Authoritative migrations live in backend/src/main/resources/db/migration/
-- =============================================================================

-- Table 1: users (V1)
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL DEFAULT 'USER',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Table 2: customers (V5)
CREATE TABLE IF NOT EXISTS customers (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES users(id),
    name        VARCHAR(255)  NOT NULL,
    email       VARCHAR(255)  NOT NULL,
    phone       VARCHAR(32)   NULL,
    company     VARCHAR(255)  NULL,
    notes       VARCHAR(1000) NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_customers_user_email (user_id, email)
);

-- Table 3: invoices (V1 + V2 + V3 + V5)
CREATE TABLE IF NOT EXISTS invoices (
    id                     BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id                BIGINT        NOT NULL REFERENCES users(id),
    customer_id            BIGINT        NULL REFERENCES customers(id),
    client_phone           VARCHAR(32)   NULL,
    paypal_invoice_id      VARCHAR(255)  NULL,
    invoice_number         VARCHAR(100)  NOT NULL,
    client_name            VARCHAR(255)  NOT NULL,
    client_email           VARCHAR(255)  NOT NULL,
    description            TEXT          NULL,
    amount                 DECIMAL(15,2) NOT NULL,
    currency               VARCHAR(10)   NOT NULL DEFAULT 'USD',
    status                 VARCHAR(50)   NOT NULL DEFAULT 'DRAFT',
    due_date               DATE          NULL,
    issued_at              DATE          NULL,
    paid_at                DATETIME      NULL,
    approved_at            DATETIME      NULL,
    approved_by_user_id    BIGINT        NULL REFERENCES users(id),
    approved_snapshot_hash VARCHAR(64)   NULL,
    payment_lock_owner     VARCHAR(64)   NULL,
    payment_lock_until     DATETIME      NULL,
    ai_extracted           BOOLEAN       NOT NULL DEFAULT FALSE,
    ai_confidence          DECIMAL(5,4)  NULL,
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_invoices_number_user (invoice_number, user_id)
);

-- Table 4: payment_links (V4) — stores SHA-256 hashes only, max 1 active link per invoice
CREATE TABLE IF NOT EXISTS payment_links (
    id                    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id            BIGINT       NOT NULL REFERENCES invoices(id),
    user_id               BIGINT       NOT NULL REFERENCES users(id),
    active_invoice_id     BIGINT       NULL UNIQUE,
    token_hash            VARCHAR(64)  NOT NULL UNIQUE,
    token_prefix          VARCHAR(16)  NOT NULL,
    invoice_snapshot_hash VARCHAR(64)  NOT NULL,
    expires_at            DATETIME     NOT NULL,
    revoked_at            DATETIME     NULL,
    revocation_reason     VARCHAR(100) NULL,
    access_count          INT          NOT NULL DEFAULT 0,
    last_accessed_at      DATETIME     NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Table 5: payment_order_intents (V3)
CREATE TABLE IF NOT EXISTS payment_order_intents (
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id            BIGINT        NOT NULL REFERENCES invoices(id),
    invoice_snapshot_hash VARCHAR(64)   NOT NULL,
    idempotency_key       VARCHAR(100)  NOT NULL UNIQUE,
    attempt_number        INT           NOT NULL,
    amount                DECIMAL(15,2) NOT NULL,
    currency              VARCHAR(10)   NOT NULL,
    status                VARCHAR(30)   NOT NULL,
    active_invoice_id     BIGINT        NULL UNIQUE,
    paypal_order_id       VARCHAR(255)  NULL,
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Table 6: payments (V1 + V2 + V3 + V4)
CREATE TABLE IF NOT EXISTS payments (
    id                      BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id              BIGINT        NOT NULL REFERENCES invoices(id),
    payment_link_id         BIGINT        NULL REFERENCES payment_links(id),
    active_invoice_id       BIGINT        NULL UNIQUE,
    paypal_order_id         VARCHAR(255)  NOT NULL UNIQUE,
    paypal_capture_id       VARCHAR(255)  NULL UNIQUE,
    invoice_snapshot_hash   VARCHAR(64)   NULL,
    idempotency_key         VARCHAR(100)  NULL UNIQUE,
    capture_idempotency_key VARCHAR(100)  NULL UNIQUE,
    paypal_approval_url     VARCHAR(1000) NULL,
    amount                  DECIMAL(15,2) NOT NULL,
    currency                VARCHAR(10)   NOT NULL DEFAULT 'USD',
    status                  VARCHAR(50)   NOT NULL DEFAULT 'CREATED',
    paypal_response_summary JSON          NULL,
    capture_requested_at    DATETIME      NULL,
    captured_at             DATETIME      NULL,
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Table 7: invoice_deliveries (V6)
CREATE TABLE IF NOT EXISTS invoice_deliveries (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id      BIGINT       NOT NULL REFERENCES invoices(id),
    user_id         BIGINT       NOT NULL REFERENCES users(id),
    payment_link_id BIGINT       NULL REFERENCES payment_links(id),
    channel         VARCHAR(20)  NOT NULL,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(255) NULL,
    status          VARCHAR(20)  NOT NULL,
    failure_reason  VARCHAR(500) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Table 8: audit_logs (V1)
CREATE TABLE IF NOT EXISTS audit_logs (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id   BIGINT       NULL,
    action      VARCHAR(100) NOT NULL,
    actor       VARCHAR(50)  NOT NULL,
    details     JSON         NULL,
    ip_address  VARCHAR(45)  NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Table 9: ai_extraction_logs (V1)
CREATE TABLE IF NOT EXISTS ai_extraction_logs (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id        BIGINT       NULL REFERENCES invoices(id),
    model_used        VARCHAR(100) NOT NULL,
    prompt_tokens     INT          NULL,
    completion_tokens INT          NULL,
    confidence_score  DECIMAL(5,4) NULL,
    validated_by_user BOOLEAN      NOT NULL DEFAULT FALSE,
    extraction_method VARCHAR(50)  NOT NULL DEFAULT 'TEXT',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
