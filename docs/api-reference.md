# PayPilot AI — API Reference

Base URL: `http://localhost:8080/api`  
Authentication: `Authorization: Bearer <JWT>` required unless explicitly noted.

---

## 1. Health & Auth

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/health` | Public | Backend health & service timestamp |
| `POST` | `/api/auth/register` | Public | Register a new seller account (`201`, returns JWT) |
| `POST` | `/api/auth/login` | Public | Authenticate seller credentials (`200`, returns JWT) |

---

## 2. Seller AI Proxy (Internal Node.js Proxy)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/api/ai/extract-invoice` | Seller JWT | Extracts nullable structured invoice draft via Gemini service & logs `ai_extraction_logs` |

---

## 3. Seller Invoice Management

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/invoices` | Seller JWT | List seller's invoices (optional `?status=DRAFT|APPROVED|PAID|...`) |
| `GET` | `/api/invoices/{id}` | Seller JWT | Get invoice details + current SHA-256 snapshot hash |
| `POST` | `/api/invoices` | Seller JWT | Create `DRAFT` invoice (links/creates seller `Customer`, optional `clientPhone` & `extractionLogId`) |
| `PUT` | `/api/invoices/{id}` | Seller JWT | Update `DRAFT` or `APPROVED` invoice (editing `APPROVED` revokes approval, active link & uncaptured orders) |
| `POST` | `/api/invoices/{id}/approve` | Seller JWT | Explicitly approve `DRAFT` against `snapshotHash` (`confirmApproval: true`) |
| `POST` | `/api/invoices/{id}/revoke-approval` | Seller JWT | Revoke approval back to `DRAFT`, locally cancelling uncaptured orders and revoking active payment link |
| `DELETE` | `/api/invoices/{id}` | Seller JWT | Delete `DRAFT` invoice when no payment or link history exists |

---

## 4. Seller Customer Directory

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/customers` | Seller JWT | List seller's customers (auto-syncs unlinked seller invoices; supports `?search=`) |
| `GET` | `/api/customers/{id}` | Seller JWT | Customer profile and owned invoices |
| `POST` | `/api/customers` | Seller JWT | Create seller customer (validates international E.164 phone `^\+[1-9]\d{6,14}$`) |
| `PUT` | `/api/customers/{id}` | Seller JWT | Update contact info (safely syncs `clientPhone` to `DRAFT`/`APPROVED` invoices without breaking snapshots) |

---

## 5. Seller Payment Links & Email Delivery

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/api/invoices/{invoiceId}/payment-links` | Seller JWT | Generate single-active 256-bit customer payment link (`expiresInDays` 1–90); revokes any prior link |
| `GET` | `/api/invoices/{invoiceId}/payment-links` | Seller JWT | List link metadata & view counts (never exposes `tokenHash` or raw tokens) |
| `POST` | `/api/invoices/{invoiceId}/payment-links/revoke` | Seller JWT | Revoke active payment link immediately |
| `POST` | `/api/invoices/{invoiceId}/email/preview` | Seller JWT | Render escaped HTML + plain-text email preview (verifies rawToken matches active link SHA-256 hash) |
| `POST` | `/api/invoices/{invoiceId}/email/send` | Seller JWT | Dispatch email via configured SMTP (`JavaMailSender`) with duplicate-send cooldown & delivery log |
| `GET` | `/api/invoices/{invoiceId}/deliveries` | Seller JWT | List sanitized email delivery audit logs for the invoice |

---

## 6. Seller PayPal Sandbox Payments

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/payments` | Seller JWT | List all seller payments across invoices (optional `?status=`) |
| `GET` | `/api/payments/invoice/{invoiceId}` | Seller JWT | Payment attempt history for a specific owned invoice |
| `POST` | `/api/payments/orders` | Seller JWT | Create/reuse PayPal Sandbox order for an owned `APPROVED` invoice |
| `POST` | `/api/payments/orders/{orderId}/capture` | Seller JWT | Capture or reconcile payment via PayPal Orders v2; marks `PAID` only when verified `COMPLETED` |
| `POST` | `/api/payments/orders/{orderId}/cancel` | Seller JWT | Cancel uncaptured order locally when capture has not been initiated |

---

## 7. Public Customer Payment Portal (`/api/public/pay/{token}`)

Unauthenticated endpoints authorized exclusively by the 256-bit URL token (hashed with SHA-256 server-side). Raw tokens are redacted from error paths and never persisted in audit logs.

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/api/public/pay/{token}` | Token | Public invoice view (`ACTIVE`, `PENDING`, `PAID` receipt, or redacted `REVOKED` / `EXPIRED` / `UNAVAILABLE`); pass `?trackView=false` for non-counting refreshes |
| `POST` | `/api/public/pay/{token}/orders` | Token | Create/reuse PayPal Sandbox order bound strictly to the link's approved snapshot and allowed redirect URLs |
| `POST` | `/api/public/pay/{token}/capture` | Token | Capture/verify customer payment via PayPal Orders v2; returns updated public state & receipt |
| `POST` | `/api/public/pay/{token}/cancel` | Token | Record customer cancellation locally and preserve retry capability |
