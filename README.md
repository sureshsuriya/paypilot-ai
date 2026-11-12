# PayPilot AI

> **AI-powered invoice & payment operations platform with separate Seller and Customer portals**  
> Built for the **PayPal AI Hackathon 2026**

PayPilot AI combines authoritative **PayPal Sandbox Orders v2** payment orchestration with **Google Gemini** natural-language invoice extraction, a private **Seller Operations Portal**, and a token-isolated **Customer Payment Portal** — enforcing explicit human approval, cryptographic snapshot verification, and backend-only capture confirmation before any invoice is marked `PAID`.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture](#architecture)
3. [Key Features](#key-features)
4. [Prerequisites](#prerequisites)
5. [Installation and Setup](#installation-and-setup)
6. [Environment Variables and API Key Setup](#environment-variables-and-api-key-setup)
7. [Running the Application](#running-the-application)
8. [Testing Instructions](#testing-instructions)
9. [API & Schema Reference](#api--schema-reference)
10. [AI Tools and Development Disclosure](#ai-tools-and-development-disclosure)
11. [Dependencies and Licenses](#dependencies-and-licenses)
12. [Hackathon Compliance & Limitations](#hackathon-compliance--limitations)

---

## Project Overview

**Problem**: Freelancers and small businesses lose time drafting invoices manually, sharing scattered payment instructions over Email or WhatsApp, and reconciling which customer payments actually settled.

**Solution**: PayPilot AI splits operations into two clearly separated experiences:

1. **Seller Portal (`/dashboard`, `/invoices`, `/invoices/create`, `/invoices/:id`, `/customers`, `/payments`)**:
   - Register/login with JWT authentication
   - Draft invoices from plain English via **Google Gemini** (`gemini-3.5-flash-lite`) or enter fields manually
   - Review every field, confirm accuracy, and explicitly approve an invoice SHA-256 snapshot before checkout or link creation
   - Generate, copy, rotate, or revoke single-active customer payment links (`/pay/:token`) stored as SHA-256 hashes only
   - Manage customer contact directories synced from invoices (with E.164 `+...` international phone validation)
   - Preview and send responsive HTML payment-link emails via configurable SMTP (`MAIL_ENABLED=true`)
   - Generate pre-filled WhatsApp Web / App click-to-chat messages (`wa.me`) with manual confirmation
   - Track unified PayPal Sandbox payments (Pending, Captured, Cancelled, Failed, Customer Link vs Seller Portal)

2. **Customer Payment Portal (`/pay/:token`)**:
   - Completely separate public layout with zero seller navigation, login requirements, or seller JWTs
   - Cryptographically random 256-bit link token verified server-side via SHA-256 hash
   - Clear customer states: Active payable invoice, Pending verification (recheck without duplicate orders), Cancelled (with safe retry), Revoked (`REVOKED`), Expired (`EXPIRED`), Unavailable, or Verified `PAID` printable receipt
   - Redirects to PayPal Sandbox Checkout and marks the invoice `PAID` only after backend verification of PayPal Orders v2 capture (`COMPLETED`)

**Core Principle**: *AI proposes draft fields. Sellers approve terms. PayPilot's Spring Boot backend verifies PayPal Sandbox settlement before any invoice becomes `PAID`.*

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│             React 18 + TypeScript + Vite + Tailwind CSS                 │
│                         (http://localhost:5173)                         │
│                                                                         │
│  ┌───────────────────────────────┐  ┌────────────────────────────────┐  │
│  │  Seller Portal (JWT-guarded)  │  │  Customer Portal (No JWT)      │  │
│  │  /dashboard, /invoices/**     │  │  /pay/:token                   │  │
│  │  /customers, /payments        │  │  publicApiClient (isolated)    │  │
│  └──────────────┬────────────────┘  └───────────────┬────────────────┘  │
└─────────────────┼───────────────────────────────────┼───────────────────┘
                  │ REST / JSON                       │ Public Token API
                  ▼                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│          Java 22 + Spring Boot 3.3.5 — Authoritative Backend            │
│                         (http://localhost:8080)                         │
│  JWT Auth | Invoice Snapshot Guard | Customer Sync | Link SHA-256 Store │
│  PayPal Sandbox REST (Orders v2) | SMTP Email | Audit Logs              │
└───────────────────┬─────────────────────────────────┬───────────────────┘
                    │ Internal REST (Secret Header)   │ JDBC (Flyway V1..V6)
                    ▼                                 ▼
┌─────────────────────────────────┐     ┌─────────────────────────────────┐
│   Node.js + TypeScript AI Proxy │     │          MySQL 8.0.45           │
│         (port 3001)             │     │ users, customers, invoices      │
│   @google/genai + Zod schemas   │     │ payment_links, payments         │
└───────────────────┬─────────────┘     │ payment_order_intents           │
                    │                   │ invoice_deliveries, audit_logs  │
                    ▼                   │ ai_extraction_logs              │
          ┌───────────────────┐         └─────────────────────────────────┘
          │   Google Gemini   │
          │ gemini-3.5-flash  │
          └───────────────────┘
```

**Security boundaries**:
- Neither the React frontend nor the Node.js AI service has PayPal API credentials or direct database write access.
- `publicApiClient` for `/pay/:token` never reads seller JWT storage or invokes seller auth logout hooks.
- Raw payment-link tokens are returned once on generation and never persisted in MySQL (only `SHA-256` hex digests + masked `ppl_...` prefixes are stored).

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 22+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js | 20+ (22 recommended) | `node -v` |
| npm | 10+ | Bundled with Node.js |
| MySQL | 8.0+ | Running locally on `localhost:3306` |

---

## Installation and Setup

### 1. Database Setup (MySQL 8)

Use **either** a local MySQL 8.0+ installation or the optional Docker Compose service:

**Option A — Existing Local MySQL 8**
```sql
CREATE DATABASE paypilot_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'paypilot_user'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON paypilot_ai.* TO 'paypilot_user'@'localhost';
FLUSH PRIVILEGES;
```

**Option B — Optional Local Docker MySQL 8 (`docker-compose.yml`)**

If you do not have MySQL installed locally (do not run if port `3306` is already used by an existing local MySQL server):

```bash
docker compose up -d mysql
```

This starts `mysql:8.0.45` with database `paypilot_ai` and user `paypilot_user` pre-created. Flyway (`backend/src/main/resources/db/migration/V1` through `V6`) manages schema creation and additive upgrades automatically on backend startup.

### 2. Configure Services

1. **Backend (`backend/.env` copied from `backend/.env.example`)**:
   Configure MySQL credentials, `JWT_SECRET`, numeric `JWT_EXPIRATION_MS` (e.g. `86400000`), `AI_SERVICE_SECRET`, PayPal Sandbox credentials (`PAYPAL_MODE=sandbox`), and optional SMTP credentials (`MAIL_ENABLED=true`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`).
2. **AI Service (`ai-service/.env` copied from `ai-service/.env.example`)**:
   Configure `GEMINI_API_KEY` (`GEMINI_MODEL=gemini-3.5-flash-lite`), `AI_SERVICE_SECRET`, or set `DEMO_MODE=true` for offline deterministic extraction.
3. **Frontend (`frontend/.env` copied from `frontend/.env.example`)**:
   Verify `VITE_API_BASE_URL=http://localhost:8080/api` and `VITE_APP_ENV=development`.

```bash
cd ai-service && npm install
cd ../frontend && npm install
```

> Never commit `.env` files to version control.

---

## Environment Variables and API Key Setup

PayPilot AI keeps all sensitive credentials out of source control by using three separate template files (`backend/.env.example`, `ai-service/.env.example`, and `frontend/.env.example`). Root `.gitignore` excludes all local `.env` files (`*.env`) while explicitly allowing `!.env.example` templates to be committed.

### 1. Copy Each `.env.example` to `.env`

Copy each template into its own service folder before starting the application:

```bash
# macOS / Linux / Git Bash
cp backend/.env.example backend/.env
cp ai-service/.env.example ai-service/.env
cp frontend/.env.example frontend/.env
```

```powershell
# Windows PowerShell
Copy-Item backend\.env.example backend\.env
Copy-Item ai-service\.env.example ai-service\.env
Copy-Item frontend\.env.example frontend\.env
```

The `.env.example` files ship with safe placeholder values only — replace every placeholder with your own credentials inside your **local `.env` files only**:

```env
# Placeholder examples in .env.example (replace these only in your local git-ignored .env files)
GEMINI_API_KEY=your_gemini_api_key
PAYPAL_CLIENT_ID=your_paypal_client_id
PAYPAL_CLIENT_SECRET=your_paypal_client_secret
JWT_SECRET=your_jwt_secret
AI_SERVICE_SECRET=your_ai_service_secret
```

> **Security rule**: Never commit actual API keys, PayPal secrets, database passwords, SMTP passwords, or JWT signing keys to GitHub. Git history and public repositories are permanently visible; `.gitignore` ignores real `.env` files so credentials stay strictly local.

### 2. Step-by-Step Credential Setup

1. **Google Gemini API Key (`ai-service/.env`)**:
   - Visit [Google AI Studio](https://aistudio.google.com/apikey), sign in, and click **Create API key**.
   - Paste it into `GEMINI_API_KEY` in `ai-service/.env` with `GEMINI_MODEL=gemini-3.5-flash-lite` and `DEMO_MODE=false` (or set `DEMO_MODE=true` for offline deterministic extraction without an API key). See [`docs/gemini-setup.md`](docs/gemini-setup.md).
2. **PayPal Sandbox Credentials (`backend/.env`)**:
   - Sign in at [PayPal Developer Dashboard](https://developer.paypal.com), navigate to **Apps & Credentials**, verify you are on the **Sandbox** tab, create a **Merchant** REST app, and copy its **Client ID** and **Client Secret** into `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET`.
   - Keep `PAYPAL_MODE=sandbox` and `PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com` (the backend rejects non-Sandbox configurations). See [`docs/paypal-setup.md`](docs/paypal-setup.md).
3. **Strong `JWT_SECRET` (`backend/.env`)**:
   - Generate a cryptographically random secret of **at least 32 characters** (`256+` bits) for HMAC-SHA256 JWT signing (`JwtUtil` rejects shorter secrets at startup):
     ```bash
     openssl rand -base64 32
     ```
     ```powershell
     $bytes = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes); [Convert]::ToBase64String($bytes)
     ```
4. **Shared `AI_SERVICE_SECRET` (`backend/.env` & `ai-service/.env`)**:
   - Set the **exact same** random secret string for `AI_SERVICE_SECRET` in both `backend/.env` and `ai-service/.env`.
   - Spring Boot sends this value in the `X-AI-Service-Secret` header, and `ai-service` validates it using constant-time `crypto.timingSafeEqual` so unauthenticated callers cannot access the internal AI microservice.
5. **MySQL 8 Connection (`backend/.env`)**:
   - Configure the exact backend datasource variables:
     - `SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/paypilot_ai?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true`
     - `SPRING_DATASOURCE_USERNAME=paypilot_user`
     - `SPRING_DATASOURCE_PASSWORD=<your_mysql_user_password>`

### Backend (`backend/.env`)

| Variable | Required | Description |
|----------|----------|-------------|
| `SPRING_DATASOURCE_URL` | Required | JDBC URL targeting `paypilot_ai` |
| `SPRING_DATASOURCE_USERNAME` | Required | MySQL user |
| `SPRING_DATASOURCE_PASSWORD` | Required | MySQL password |
| `PAYPAL_CLIENT_ID` | Required | PayPal Sandbox REST App Client ID |
| `PAYPAL_CLIENT_SECRET` | Required | PayPal Sandbox REST App Secret |
| `PAYPAL_MODE` | Required | Must remain `sandbox` |
| `PAYPAL_BASE_URL` | Optional | Defaults to `https://api-m.sandbox.paypal.com` |
| `PAYPAL_WEBHOOK_ID` | Optional | Reserved PayPal webhook ID property (`your_paypal_webhook_id` placeholder) |
| `JWT_SECRET` | Required | Minimum 32-character secret for HMAC-SHA256 JWTs |
| `JWT_EXPIRATION_MS` | Optional | Token lifetime in numeric milliseconds (e.g. `86400000` for 24h; defaults to `86400000`) |
| `AI_SERVICE_URL` | Required | Internal Node.js AI URL (`http://localhost:3001`) |
| `AI_SERVICE_SECRET` | Required | Shared secret matching `ai-service/.env` |
| `GEMINI_MODEL` | Optional | Default model name fallback logged by backend (`gemini-3.5-flash-lite` default) |
| `SERVER_PORT` | Optional | Backend HTTP port (defaults to `8080`) |
| `CORS_ALLOWED_ORIGINS` | Optional | Comma-separated allowed frontend origins (defaults to `http://localhost:5173`) |
| `MAIL_ENABLED` | Optional | Set `true` to enable real SMTP email delivery (`false` default) |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | Optional | Standard SMTP server settings when `MAIL_ENABLED=true` |
| `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS` | Optional | SMTP auth (`true` default) and STARTTLS (`true` default) toggles |
| `MAIL_RESEND_COOLDOWN_SECONDS` | Optional | Duplicate-email cooldown window in seconds (defaults to `60`) |

### AI Service (`ai-service/.env`)

| Variable | Required | Description |
|----------|----------|-------------|
| `GEMINI_API_KEY` | Live mode | Google AI Studio Gemini API key |
| `GEMINI_MODEL` | Optional | Defaults to `gemini-3.5-flash-lite` |
| `DEMO_MODE` | Optional | `true` enables deterministic local extraction without external calls |
| `AI_SERVICE_PORT` | Optional | Defaults to `3001` |
| `BACKEND_URL` | Optional | Spring Boot backend URL (`http://localhost:8080` default) |
| `AI_SERVICE_SECRET` | Required | Must match backend `AI_SERVICE_SECRET` |

### Frontend (`frontend/.env`)

| Variable | Required | Description |
|----------|----------|-------------|
| `VITE_API_BASE_URL` | Optional | Spring Boot API base URL (`http://localhost:8080/api` or `/api` via Vite dev proxy) |
| `VITE_APP_ENV` | Optional | Frontend environment indicator (`development` default) |

---

## Running the Application

Open three terminals:

```bash
# Terminal 1 — Spring Boot Backend (:8080)
cd backend
mvn spring-boot:run

# Terminal 2 — Gemini AI Microservice (:3001)
cd ai-service
npm run dev

# Terminal 3 — React Frontend (:5173)
cd frontend
npm run dev
```

Visit `http://localhost:5173` for the Seller Portal, or open a generated customer link at `http://localhost:5173/pay/<token>` for the Customer Payment Portal.

---

## Testing Instructions

All automated tests run locally without charging real money, mutating live `paypilot_ai` data, calling live PayPal Checkout, or sending real SMTP emails:

```bash
# Backend unit & integration tests (H2 MySQL mode + isolated MySQL schema tests)
cd backend
mvn test

# Frontend unit/component tests, TypeScript check (`lint` runs `tsc --noEmit`), and production bundle build
cd frontend
npm test
npm run lint
npm run build

# AI service TypeScript check, mocked Gemini unit tests, and build (ESLint not configured)
cd ai-service
npm run typecheck
npm test
npm run build
```

See [`docs/demo-instructions.md`](docs/demo-instructions.md), [`docs/gemini-setup.md`](docs/gemini-setup.md), and [`docs/paypal-setup.md`](docs/paypal-setup.md) for setup and walkthrough steps.

---

## API & Schema Reference

- REST API Reference: [`docs/api-reference.md`](docs/api-reference.md)
- Reference SQL Schema (`V1`–`V6`): [`database/schema-reference.sql`](database/schema-reference.sql)

---

## AI Tools and Development Disclosure

### Runtime AI

| Component | Provider | Model / SDK | Purpose |
|-----------|----------|-------------|---------|
| Natural-language Invoice Extraction | Google Gemini | `gemini-3.5-flash-lite` via `@google/genai` | Extracts structured nullable invoice draft fields validated with Zod before user review and approval |

### Development AI Disclosure

- Built with AI-assisted coding and reviewed, verified, and tested end-to-end by the project author.

---

## Dependencies and Licenses

- **Backend**: Spring Boot 3.3.5 (Web, Security, Data JPA, Validation, Mail), Flyway + Flyway MySQL, MySQL Connector/J, JJWT, Spring Dotenv, Lombok, H2 (test scope).
- **AI Service**: Express, `@google/genai`, Zod, dotenv, TypeScript, tsx.
- **Frontend**: React 18, TypeScript, Vite 5, Tailwind CSS, Axios, React Router v6, Vitest + React Testing Library.

All third-party libraries use permissive open-source licenses (MIT, Apache 2.0, BSD, EPL/MPL) compatible with this repository's [`LICENSE`](LICENSE).

---

## Hackathon Compliance & Limitations

- See [`docs/hackathon-compliance.md`](docs/hackathon-compliance.md).
- **Sandbox only**: All checkout flows target `https://api-m.sandbox.paypal.com`. Live money movement is never enabled.
- **SMTP optional**: HTML/plain-text email preview works out of the box; real email sending requires SMTP configuration (`MAIL_ENABLED=true`).
- **WhatsApp click-to-chat**: Opens a pre-filled `wa.me` draft requiring manual seller confirmation inside WhatsApp; never claims sent status automatically.
