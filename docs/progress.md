# PayPilot AI — Project Implementation Progress
## PayPal AI Hackathon 2026

> **Current status**: **Two-Portal Platform Complete (Phases 1–6)** — verified across backend (`mvn test`), frontend (`vitest` / `tsc` / `vite build`), AI service (`tsc` / `node --test`), MySQL `V1`–`V6` migrations, and live PayPal Sandbox + Gemini workflows.  
> **Note**: Commit history (`e789e08`..`72a801a`) covers initial scaffolding and early Phase 4 extraction when OpenAI was prototyped; the platform subsequently migrated to **Google Gemini (`@google/genai`)** and added Phases 5–6 and the two-portal experience.

---

## Completed Milestones

### Phase 1 — Scaffolding, Compliance & Architecture
- Multi-service workspace (`frontend` React/Vite/Tailwind, `backend` Spring Boot 3.3.5 / Java 22, `ai-service` Node.js 22 / Express / TypeScript, MySQL 8.0.45).
- Strict secret isolation (`.gitignore`, `.env.example` templates, no secrets in frontend or repository).

### Phase 2 — Core Database Layer & Immutable Audit Trail
- Flyway schema [`V1__init_schema.sql`](../backend/src/main/resources/db/migration/V1__init_schema.sql) creating `users`, `invoices`, `payments`, `audit_logs`, and `ai_extraction_logs`.
- JPA entities with `BigDecimal` (`DECIMAL(15,2)`) monetary fields and append-only `AuditLog` (`@Immutable` + `@PreUpdate` protection + sensitive key redaction).

### Phase 3 — JWT Auth & Seller Invoice CRUD APIs
- Stateless JWT authentication (`POST /api/auth/register`, `POST /api/auth/login`) and owner-scoped Seller invoice management (`GET/POST/PUT/DELETE /api/invoices`).

### Phase 4 — Google Gemini Natural-Language Extraction
- Node.js `ai-service` (`@google/genai`, default `gemini-3.5-flash-lite`) with strict structured JSON output schema, Zod validation, `timingSafeEqual` `X-AI-Service-Secret` verification, and offline `DEMO_MODE=true` deterministic parser.
- Spring Boot proxy `POST /api/ai/extract-invoice` with authoritative Java re-validation and human-review draft confirmation (`AiExtractionLog`).

### Phase 5 — Human Approval Guards, Cross-Instance Leases & PayPal Sandbox Orders v2
- Flyway [`V2`](../backend/src/main/resources/db/migration/V2__invoice_approval_and_payment_guards.sql) and [`V3`](../backend/src/main/resources/db/migration/V3__cross_instance_payment_guards.sql) adding cryptographic invoice SHA-256 snapshot hashes (`approved_snapshot_hash`), atomic payment lease columns (`payment_lock_owner`, `payment_lock_until`), and `payment_order_intents` idempotency guards.
- Direct REST v2 integration in `PayPalSandboxClient` (`PAYPAL_MODE=sandbox` enforced fail-closed against `https://api-m.sandbox.paypal.com`), order creation, capture verification (`COMPLETED` + amount + currency check), `PENDING` safe re-checking, and local cancellation recovery.

### Phase 6 — Two-Portal Experience, Customer Directory & Multi-Channel Delivery
- **Customer Payment Links (`V4`)**: Single-active 256-bit cryptographically random `/pay/:token` links stored exclusively as SHA-256 hex hashes (`payment_links`), link rotation/revocation, strict view-count tracking (`?trackView=false` on refreshes/callbacks so StrictMode and reloads never double-count), and isolated public endpoints (`/api/public/pay/{token}`).
- **Customer Directory (`V5`)**: Seller-isolated `customers` table with auto-sync from invoices and international E.164 phone validation (`PhoneNumberValidator`).
- **Email & WhatsApp Delivery (`V6`)**: Server-rendered HTML/plain-text invoice email preview, optional SMTP dispatch (`MAIL_ENABLED=true`) with cooldown & `invoice_deliveries` audit logs, plus manual WhatsApp click-to-chat (`wa.me`) drafting.
- **Two Separate Portals**:
  - **Seller Portal**: `/login`, `/register`, `/dashboard`, `/invoices`, `/invoices/create`, `/invoices/:id`, `/customers`, `/payments`.
  - **Customer Payment Portal**: Token-only public `/pay/:token` with zero seller chrome or JWTs, strict `SANDBOX` labeling, checkout redirect recovery, and verified `PAID` printable receipt.

---

## Remaining Release & Submission Items

- [ ] Complete repository hygiene cleanup, commit working tree, and push to public GitHub repository
- [ ] Record end-to-end two-portal demo video and complete Devpost submission
