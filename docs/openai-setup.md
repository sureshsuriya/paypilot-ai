# PayPilot AI — OpenAI API Setup Guide

---

## Overview

PayPilot AI uses the **OpenAI GPT-4o** model for four AI agents:
1. **Invoice Extraction Agent** — extracts structured data from PDFs
2. **Payment Reconciliation Agent** — matches invoices to payments
3. **Payment Operations Agent** — proposes PayPal actions (human approves)
4. **Financial Explanation Agent** — plain-language summaries

If you do not have an OpenAI API key, you can run the application in **Demo Mode** without any AI costs.

---

## Option A — Use Demo Mode (No API Key Required)

Set `DEMO_MODE=true` in `ai-service/.env`:

```
DEMO_MODE=true
```

In Demo Mode:
- All AI features display realistic pre-built responses
- Human approval gates, audit logging, and AI labeling work identically
- No OpenAI API calls are made and no cost is incurred
- The UI clearly shows `[DEMO]` next to AI-generated content

**This is the recommended option for judges who don't have an OpenAI account.**

---

## Option B — Live OpenAI GPT-4o

### Step 1 — Create an OpenAI Account

1. Go to [https://platform.openai.com](https://platform.openai.com)
2. Create a free account or sign in

### Step 2 — Add Credits

GPT-4o requires a paid API plan. Add at least \$5 of credits:
1. Go to **Settings** → **Billing**
2. Add a payment method and purchase credits

**Estimated cost for a typical demo session** (5–10 AI operations): **\$0.05–\$0.25**

### Step 3 — Create an API Key

1. Go to **API Keys** in the left sidebar (or at platform.openai.com/api-keys)
2. Click **Create new secret key**
3. Give it a name (e.g., `PayPilot AI`)
4. Copy the key immediately — you cannot view it again

### Step 4 — Configure the AI Service

Add to `ai-service/.env`:

```
OPENAI_API_KEY=sk-...your_actual_key_here
OPENAI_MODEL=gpt-4o
DEMO_MODE=false
```

> ⚠️ **Never commit `ai-service/.env` to version control.**  
> ⚠️ **Never put the OpenAI key in the frontend or in any `VITE_` variable.**  
> The key is only accessible to the Node.js AI service on the server.

### Step 5 — Verify the Configuration

Start the AI service:
```bash
cd ai-service
npm run dev
```

Check the health endpoint:
```
GET http://localhost:3001/health
```

Expected response (live mode):
```json
{
  "status": "ok",
  "service": "paypilot-ai-service",
  "mode": "live",
  "model": "gpt-4o",
  "timestamp": "..."
}
```

---

## Model Configuration

The model is configurable via environment variable:

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENAI_MODEL` | `gpt-4o` | The OpenAI model to use |

Supported values: `gpt-4o`, `gpt-4o-mini` (lower cost, slightly reduced quality)

---

## AI Safety Guarantees

Regardless of which mode you use, PayPilot AI enforces these safety rules:

| Rule | How it's enforced |
|------|------------------|
| AI never invents financial figures | Amounts always come from the database, not AI |
| AI outputs are validated | Zod schema validation on every AI response |
| Human approval required | No payment executes without explicit user approval |
| AI outputs labeled | UI clearly marks all AI-generated content |
| Confidence score shown | Extraction confidence is displayed to users |
| AI cannot access PayPal | Only Spring Boot backend calls PayPal APIs |

---

## Troubleshooting

| Error | Solution |
|-------|---------|
| `401 Unauthorized` from OpenAI | Check OPENAI_API_KEY is correct and not expired |
| `429 Too Many Requests` | Rate limited — wait a moment and retry |
| `insufficient_quota` | Add credits to your OpenAI account |
| AI service won't start | Check all env vars in `ai-service/.env` are set |

