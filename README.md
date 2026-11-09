# PayPilot AI

> **Agentic invoice and payment management for freelancers and small businesses**  
> Built for the **PayPal AI Hackathon 2026**

PayPilot AI combines genuine PayPal Sandbox integration with GPT-4o–powered AI agents to automate invoice extraction, payment reconciliation, and intelligent payment workflows — with mandatory human approval before any financial action is executed.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture](#architecture)
3. [Prerequisites](#prerequisites)
4. [Installation and Setup](#installation-and-setup)
5. [Environment Variables](#environment-variables)
6. [PayPal Sandbox Setup](#paypal-sandbox-setup)
7. [OpenAI API Setup](#openai-api-setup)
8. [Running the Application](#running-the-application)
9. [AI Tools and Development Disclosure](#ai-tools-and-development-disclosure)
10. [AI Features](#ai-features)
11. [PayPal Integration](#paypal-integration)
12. [Testing Instructions](#testing-instructions)
13. [Demo Instructions](#demo-instructions)
14. [Project Structure](#project-structure)
15. [API Reference](#api-reference)
16. [Dependencies and Licenses](#dependencies-and-licenses)
17. [Hackathon Compliance](#hackathon-compliance)
18. [Known Limitations](#known-limitations)

---

## Project Overview

**Problem**: Freelancers and small businesses spend hours each week manually creating invoices, chasing payments, and reconciling bank statements with their accounting records.

**Solution**: PayPilot AI is a full-stack application that:
- Extracts invoice data from uploaded PDFs using GPT-4o vision
- Creates and tracks invoices through the PayPal Invoicing API
- Collects payments via the PayPal Orders API
- Provides an AI reconciliation agent that matches payments to invoices and flags discrepancies
- Offers an agentic payment workflow where the AI proposes an action, the user approves, and the system executes through PayPal

**Key design principle**: The AI makes recommendations. Humans make decisions. PayPal executes payments.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│              React + TypeScript + Vite                  │
│                     (port 5173)                         │
└──────────────────────┬──────────────────────────────────┘
                       │ REST/JSON
                       ▼
┌─────────────────────────────────────────────────────────┐
│          Java 22 + Spring Boot — Business Backend       │
│  (port 8080) — owns ALL PayPal API calls and DB writes  │
│  Invoicing API | Orders API | Webhooks | Audit Logs     │
└──────────┬────────────────────────────┬─────────────────┘
           │ Internal REST              │ JDBC
           ▼                            ▼
┌─────────────────────┐      ┌──────────────────────┐
│  Node.js + TS       │      │    MySQL 8            │
│  AI Orchestration   │      │  invoices | payments  │
│  (port 3001)        │      │  audit_logs           │
│  GPT-4o agents      │      │  ai_extraction_logs   │
└─────────────────────┘      └──────────────────────┘
           │
           ▼
┌──────────────────────┐
│   OpenAI GPT-4o      │
│   (external API)     │
└──────────────────────┘
```

**AI service has zero direct PayPal access.** All payment operations flow through Spring Boot after explicit human approval.

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 22+ | `java -version` to verify |
| Maven | 3.9+ | `mvn -version` to verify |
| Node.js | 20+ | `node -version` to verify |
| npm | 10+ | bundled with Node.js |
| MySQL | 8.0+ | Running locally or via Docker |
| Git | any | For cloning |

---

## Installation and Setup

### 1. Clone the repository

```bash
git clone https://github.com/YOUR_USERNAME/paypilot-ai.git
cd paypilot-ai
```

### 2. Set up MySQL database

```sql
CREATE DATABASE paypilot_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'paypilot_user'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON paypilot_ai.* TO 'paypilot_user'@'localhost';
FLUSH PRIVILEGES;
```

The Spring Boot backend uses **Flyway** to automatically apply database migrations on startup. Do not manually run `schema.sql` for a fresh setup.

### 3. Configure the backend

```bash
cd backend
cp .env.example .env
# Edit .env with your PayPal Sandbox credentials and DB password
```

See [Environment Variables — Backend](#backend-environment-variables) below.

### 4. Configure the AI service

```bash
cd ai-service
cp .env.example .env
# Edit .env with your OpenAI API key
npm install
```

See [Environment Variables — AI Service](#ai-service-environment-variables) below.

### 5. Configure the frontend

```bash
cd frontend
cp .env.example .env
# Edit .env if running services on non-default ports
npm install
```

---

## Environment Variables

### Backend Environment Variables

File: `backend/.env` (copy from `backend/.env.example`)

| Variable | Required | Description |
|----------|----------|-------------|
| `PAYPAL_CLIENT_ID` | ✅ | PayPal Sandbox app Client ID |
| `PAYPAL_CLIENT_SECRET` | ✅ | PayPal Sandbox app Client Secret |
| `PAYPAL_MODE` | ✅ | Must be `sandbox` |
| `JWT_SECRET` | ✅ | Minimum 32-character secret for JWT signing |
| `SPRING_DATASOURCE_URL` | ✅ | e.g. `jdbc:mysql://localhost:3306/paypilot_ai` |
| `SPRING_DATASOURCE_USERNAME` | ✅ | MySQL username |
| `SPRING_DATASOURCE_PASSWORD` | ✅ | MySQL password |
| `AI_SERVICE_URL` | ✅ | e.g. `http://localhost:3001` |
| `PAYPAL_WEBHOOK_ID` | Optional | Webhook ID for signature verification |

> ⚠️ **Never commit `backend/.env` to version control.**

### AI Service Environment Variables

File: `ai-service/.env` (copy from `ai-service/.env.example`)

| Variable | Required | Description |
|----------|----------|-------------|
| `OPENAI_API_KEY` | ✅ (or use demo mode) | Your OpenAI API key |
| `OPENAI_MODEL` | Optional | Defaults to `gpt-4o` |
| `DEMO_MODE` | Optional | Set `true` to use mock AI responses (no API key needed) |
| `BACKEND_URL` | ✅ | e.g. `http://localhost:8080` |
| `AI_SERVICE_PORT` | Optional | Defaults to `3001` |

> ⚠️ **Never commit `ai-service/.env` to version control.**

**Demo Mode**: If `DEMO_MODE=true`, the AI service returns pre-built structured responses without calling the OpenAI API. This is useful for judges who do not have an OpenAI key. All UI labels, human approval gates, and audit logging work identically in demo mode.

### Frontend Environment Variables

File: `frontend/.env` (copy from `frontend/.env.example`)

| Variable | Required | Description |
|----------|----------|-------------|
| `VITE_API_BASE_URL` | ✅ | Backend URL, e.g. `http://localhost:8080/api` |
| `VITE_APP_ENV` | Optional | `development` or `production` |

> The frontend communicates exclusively with the Spring Boot backend. All AI requests are proxied through the backend — the Node.js AI service is never exposed to the browser directly. No API keys or secrets are ever sent to the frontend.

---

## PayPal Sandbox Setup

See [`docs/paypal-setup.md`](docs/paypal-setup.md) for complete step-by-step instructions.

**Quick steps:**
1. Create a free account at [developer.paypal.com](https://developer.paypal.com)
2. Go to **My Apps & Credentials** → **Sandbox**
3. Create a new app — copy the **Client ID** and **Secret**
4. Note your **Sandbox Business Account** email (payer in tests)
5. Note your **Sandbox Personal Account** email (invoice recipient in tests)
6. Set `PAYPAL_MODE=sandbox` — never use live credentials

All transactions are clearly identified as **[SANDBOX]** in the UI.

---

## OpenAI API Setup

See [`docs/openai-setup.md`](docs/openai-setup.md) for detailed instructions.

**Quick steps:**
1. Create an account at [platform.openai.com](https://platform.openai.com)
2. Go to **API Keys** and create a new key
3. Add the key as `OPENAI_API_KEY` in `ai-service/.env`
4. The application uses `gpt-4o` by default (configurable via `OPENAI_MODEL`)

**Cost estimate**: A typical demo session (5–10 AI operations) uses approximately \$0.05–\$0.20 of OpenAI credits.

**No OpenAI key?** Set `DEMO_MODE=true` to run with mock AI responses that demonstrate all UI flows identically.

---

## Running the Application

Open three terminal windows:

**Terminal 1 — Backend (Spring Boot)**
```bash
cd backend
mvn spring-boot:run
# Starts on http://localhost:8080
# Flyway automatically applies DB migrations
```

**Terminal 2 — AI Service (Node.js)**
```bash
cd ai-service
npm run dev
# Starts on http://localhost:3001
```

**Terminal 3 — Frontend (React + Vite)**
```bash
cd frontend
npm run dev
# Opens at http://localhost:5173
```

Navigate to [http://localhost:5173](http://localhost:5173) to use the application.

---

## AI Tools and Development Disclosure

### Runtime AI — Application Features

| AI Component | Provider | Model | Purpose |
|-------------|----------|-------|---------|
| Invoice Extraction Agent | OpenAI | GPT-4o | Extracts structured invoice data from uploaded PDF/image files using vision and structured outputs |
| Payment Reconciliation Agent | OpenAI | GPT-4o | Analyzes invoice and payment records from the database to identify matches, gaps, and discrepancies |
| Payment Operations Agent | OpenAI | GPT-4o | Uses tool/function calling to propose PayPal payment actions; requires explicit human approval before execution |
| Financial Explanation Agent | OpenAI | GPT-4o | Generates plain-language summaries of invoice and payment status from verified database records |

**Important**: All runtime AI outputs are:
- Clearly labeled as AI-generated in the UI
- Validated against Zod schemas before use
- Based on verified database records (AI never invents financial figures)
- Subject to human approval before triggering financial actions

### Development AI — Tools Used to Build This Project

| Tool | Provider | Use in Development |
|------|----------|-------------------|
| Antigravity (AI coding assistant) | Google DeepMind | Architecture planning, code generation, documentation, debugging, compliance review throughout development |
| ChatGPT | OpenAI | Additional development assistance and code review |

**Disclosure notes:**
- All generated code was reviewed, tested, and is understood by the project author
- The project author is responsible for all submitted code
- These development tools are distinct from the runtime AI features used in the application
- No AI tool was falsely claimed to be integrated into the application
- The model used for runtime AI (`gpt-4o`) is accurately documented above

---

## AI Features

### 1. Invoice Extraction
Upload a PDF or image of an invoice. The AI extracts:
- Vendor name and contact information
- Invoice number, date, and due date
- Line items with descriptions and amounts
- Total amount and currency

**Output is labeled "AI-extracted — please verify before saving."** Users must confirm extracted data before it is saved.

### 2. Payment Reconciliation
The AI reviews your invoices and PayPal payment records to:
- Match payments to invoices
- Identify unpaid or overdue invoices
- Flag duplicate payments or amount mismatches

**Output is labeled "AI analysis — verify with your records."** No data is changed automatically.

### 3. Agentic Payment Workflow
1. User describes an intent (e.g., "Pay invoice #42 for Acme Corp")
2. AI identifies the invoice, constructs a PayPal payment proposal
3. AI presents the proposal to the user for review
4. User clicks **Approve** to authorize
5. Spring Boot executes the PayPal API call
6. Result is logged in the audit trail

**The AI never executes a payment without explicit user approval.**

### 4. Financial Explanations
Ask questions about your invoices and payments in plain language. The AI answers using only verified data from the database.

---

## PayPal Integration

PayPilot AI integrates the following PayPal Sandbox APIs:

| Feature | API Endpoint | Implementation |
|---------|-------------|----------------|
| Create invoice | `POST /v2/invoicing/invoices` | `InvoiceService.java` |
| Send invoice to client | `POST /v2/invoicing/invoices/{id}/send` | `InvoiceService.java` |
| Get invoice status | `GET /v2/invoicing/invoices/{id}` | `InvoiceService.java` |
| Create payment order | `POST /v2/checkout/orders` | `PaymentService.java` |
| Capture payment | `POST /v2/checkout/orders/{id}/capture` | `PaymentService.java` |
| Payment webhook | `POST /api/webhooks/paypal` | `WebhookController.java` |

**All PayPal API calls are made exclusively from the Spring Boot backend.** The AI service and React frontend have no PayPal API access.

**Invoice status is set to PAID only when the PayPal capture response returns `status: COMPLETED`.**

---

## Testing Instructions

See [`docs/demo-instructions.md`](docs/demo-instructions.md) for the complete judge testing guide.

### Quick Test Flow

1. **Register** a test account at `http://localhost:5173`
2. **Create an invoice** manually or by uploading a sample PDF
3. **Send the invoice** via PayPal (creates a PayPal Sandbox invoice)
4. **Collect payment** using your PayPal Sandbox Personal Account
5. **Verify** the invoice status updates to PAID after capture
6. **Run reconciliation** using the AI agent
7. **Ask the Financial Explanation Agent** a question about your invoices

### Sample Sandbox Accounts
See `docs/paypal-setup.md` for how to find your sandbox test accounts.

### Demo Mode (No OpenAI Key)
Set `DEMO_MODE=true` in `ai-service/.env` to run without an OpenAI API key. All AI features display realistic mock responses with identical UI behavior.

---

## Project Structure

```
paypilot-ai/
├── frontend/          # React 18 + TypeScript + Vite + Tailwind CSS
├── backend/           # Java 22 + Spring Boot + Maven (direct PayPal REST)
├── ai-service/        # Node.js + TypeScript + Express + OpenAI SDK
├── docs/              # Setup guides, API reference, compliance docs
├── database/          # Reference schema (Flyway manages migrations)
├── README.md
├── LICENSE            # MIT License
├── .gitignore
└── docker-compose.yml # Optional local dev environment
```

---

## API Reference

See [`docs/api-reference.md`](docs/api-reference.md) for the full API documentation.

**Backend base URL**: `http://localhost:8080/api`

> **Note**: The frontend never communicates with the AI service directly. All AI requests are routed through the Spring Boot backend (`/api/ai/*`), which calls the internal Node.js AI service on the server side.

---

## Dependencies and Licenses

### Backend (Java / Maven)
| Dependency | License |
|------------|---------|
| Spring Boot 3.x | Apache 2.0 |
| Spring Security | Apache 2.0 |
| Spring Data JPA | Apache 2.0 |
| Flyway | Apache 2.0 |
| MySQL Connector/J | GPL 2.0 with FOSS exception |
| PayPal REST SDK (Java) | Apache 2.0 | Note: direct REST via Spring's RestClient — no third-party PayPal SDK dependency |
| JJWT (JWT library) | Apache 2.0 |
| Jackson (JSON) | Apache 2.0 |

### AI Service (Node.js)
| Dependency | License |
|------------|---------|
| Express | MIT |
| OpenAI Node.js SDK | MIT |
| Zod | MIT |
| TypeScript | Apache 2.0 |
| tsx | MIT |
| dotenv | BSD-2-Clause |

### Frontend (React)
| Dependency | License |
|------------|---------|
| React 18 | MIT |
| TypeScript | Apache 2.0 |
| Vite | MIT |
| Tailwind CSS | MIT |
| Axios | MIT |
| React Router | MIT |

All dependencies are open-source with permissive licenses compatible with this project.

---

## Hackathon Compliance

See [`docs/hackathon-compliance.md`](docs/hackathon-compliance.md) for the full compliance checklist required by the PayPal AI Hackathon 2026 rules.

---

## Known Limitations

- **PayPal Sandbox only**: This application uses PayPal Sandbox and is not configured for live payments.
- **OpenAI API cost**: Runtime AI features require an OpenAI API key with available credits. Use `DEMO_MODE=true` for cost-free testing.
- **PDF extraction accuracy**: AI invoice extraction works best with clearly formatted, text-based PDFs. Handwritten or heavily formatted documents may have lower accuracy.
- **Single currency**: The current implementation uses USD. Multi-currency support is planned.
- **No email sending**: Invoice "sending" creates a PayPal Sandbox invoice; actual email delivery depends on sandbox configuration.

---

## License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE) for details.

---

*Built for the PayPal AI Hackathon 2026 | Sandbox transactions only | AI outputs are clearly labeled and require human approval for financial actions*

