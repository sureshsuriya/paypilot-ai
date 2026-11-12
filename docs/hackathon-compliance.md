# PayPilot AI — Hackathon Compliance Checklist
## PayPal AI Hackathon 2026

> **Last updated**: 2026-11-12 (Phases 1–6 Complete)  
> **Status legend**: ✅ PASS | ❌ FAIL | ⏳ NOT VERIFIED

---

## 1. Mandatory Project Requirements & PayPal Integration

| # | Requirement | Implementation Evidence | Verification Method | Status |
|---|------------|------------------------|--------------------|--------|
| 1.1 | PayPal Developer Sandbox Orders v2 integration | Direct `RestClient` integration in `PayPalSandboxClient.java` against `api-m.sandbox.paypal.com` (`PAYPAL_MODE=sandbox`) | Spring Boot backend tests + live Sandbox verified $750 invoice flow | ✅ PASS |
| 1.2 | Meaningful AI integrated into workflow | Internal Node.js service using `@google/genai` (`gemini-3.5-flash-lite`) + Zod schema validation | `ai-service` tests + `AiExtractionControllerIntegrationTest` | ✅ PASS |
| 1.3 | Backend-only payment truth (`PAID` only on verified capture) | `PaymentService.finalizeCaptureVerification` checks order ID, capture ID, amount, currency & `COMPLETED` status before updating `invoices.status = PAID` | `PaymentControllerIntegrationTest`, `PublicPaymentControllerIntegrationTest`, `MySqlCrossInstancePaymentIntegrationTest` | ✅ PASS |
| 1.4 | Secrets never exposed to browser or Git | PayPal, JWT, MySQL, AI secret, SMTP credentials stored only in `.env` (`.gitignore`); payment link tokens stored only as SHA-256 hex digests | Code inspection & unit/integration assertions | ✅ PASS |
| 1.5 | Explicit human approval & snapshot protection | `InvoiceSnapshotService` computes canonical SHA-256 hash; edits or revocations invalidate uncaptured orders and active payment links | `InvoiceApprovalIntegrationTest`, `PaymentLinkServiceTest` | ✅ PASS |
| 1.6 | Separate Seller & Customer Portals | Seller Portal protected by JWT (`/dashboard`, `/invoices/**`, `/customers`, `/payments`); Customer Portal (`/pay/:token`) uses isolated `publicApiClient` with zero seller nav/JWT | `CustomerPaymentPage.test.tsx`, `SellerPaymentLinkAndCustomerControllerIntegrationTest`, `PublicPaymentControllerIntegrationTest` | ✅ PASS |
| 1.7 | Concurrency & idempotency across instances | DB atomic payment lease + `payment_order_intents` unique constraints + MySQL multi-context concurrency suite | `MySqlCrossInstancePaymentIntegrationTest` (against MySQL 8.0) | ✅ PASS |

---

## 2. Automated Verification Coverage

| Suite | Command | Scope | Status |
|-------|---------|-------|--------|
| Backend (Java 22 / Spring Boot) | `mvn test` | Flyway V1–V6, repository constraints, PayPal Sandbox client, Seller & Public link APIs, Customer sync & E.164 phone validation, Email HTML escaping/cooldown/mock SMTP, MySQL multi-instance leases | ✅ PASS |
| Frontend (React / Vite / TS) | `npm test && npm run lint && npm run build` | Customer Portal isolation/checkout/errors, Seller Dashboard metrics, Customer CRUD/phone validation, Payment-link rotation/revocation, Email modal, WhatsApp URL/message/manual-send guard | ✅ PASS |
| AI Service (Node.js / TS) | `npm run typecheck && npm test && npm run build` | Gemini structured extraction schema validation, quota error mapping, DEMO_MODE deterministic fallback | ✅ PASS |

---

## 3. Pre-Submission Checklist (User Managed)

| # | Item | Status |
|---|------|--------|
| 3.1 | Final `git commit` / public GitHub push (when user approves) | ⏳ NOT VERIFIED |
| 3.2 | Final screen-recorded walkthrough & Devpost submission fields | ⏳ NOT VERIFIED |
