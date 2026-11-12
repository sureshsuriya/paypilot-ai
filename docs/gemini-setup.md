# PayPilot AI — Google Gemini Setup Guide

> Internal AI microservice (`ai-service`, port `3001`) powered by **Google Gemini** (`@google/genai`).

---

## Overview

PayPilot AI uses **Google Gemini** (`gemini-3.5-flash-lite` by default) to extract structured invoice draft fields from plain-English seller prompts.

**Security & financial boundaries**:
- The React frontend **never** calls `ai-service` or Google Gemini directly.
- All extraction requests pass through the authenticated Spring Boot endpoint `POST /api/ai/extract-invoice`, which authenticates to `ai-service` using the shared `X-AI-Service-Secret` header.
- Extracted fields are strictly validated with Zod in Node.js and re-validated in Java (`BigDecimal` precision, ISO currency, email, future/present due date).
- AI never auto-saves invoices, approves invoices, or triggers PayPal API calls.

---

## Option A — Demo Mode (No Gemini API Key Required)

For offline testing or judge evaluation without a Google AI Studio key, set `DEMO_MODE=true` in `ai-service/.env`:

```env
DEMO_MODE=true
AI_SERVICE_PORT=3001
BACKEND_URL=http://localhost:8080
AI_SERVICE_SECRET=replace_with_shared_secret_here
```

In Demo Mode:
- `ai-service` runs a deterministic local parser (`extractInvoiceInDemoMode`) without calling external Gemini APIs.
- Human review, missing-field warnings, audit logging, and approval gates work identically.
- Missing fields (such as client email or amount) are left `null` and never fabricated.

---

## Option B — Live Google Gemini API (Free Tier Supported)

### Step 1 — Create a Gemini API Key

1. Open [Google AI Studio](https://aistudio.google.com/apikey) and sign in.
2. Click **Create API key** and copy the generated key.

### Step 2 — Configure `ai-service/.env`

Copy `ai-service/.env.example` to `ai-service/.env` and configure:

```env
GEMINI_API_KEY=your_gemini_api_key_here
GEMINI_MODEL=gemini-3.5-flash-lite
DEMO_MODE=false
AI_SERVICE_PORT=3001
BACKEND_URL=http://localhost:8080
AI_SERVICE_SECRET=replace_with_shared_secret_here
```

> Ensure `AI_SERVICE_SECRET` in `ai-service/.env` matches `AI_SERVICE_SECRET` in `backend/.env`.
> Never commit `.env` files or place `GEMINI_API_KEY` in `frontend/.env` or any `VITE_` variable.

### Step 3 — Start and Verify the Service

```bash
cd ai-service
npm install
npm run dev
```

Check readiness:

```http
GET http://localhost:3001/health
```

Expected response in live mode:

```json
{
  "status": "ok",
  "service": "paypilot-ai-service",
  "mode": "live",
  "model": "gemini-3.5-flash-lite",
  "timestamp": "..."
}
```

---

## Verification Commands

```bash
cd ai-service
npm run typecheck
npm test
npm run build
```

All automated tests inject a mock Gemini client or exercise `DEMO_MODE=true`, so they run cleanly without network calls or quota consumption.

