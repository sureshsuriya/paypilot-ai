# PayPilot AI — Demo and Judge Testing Instructions
## PayPal AI Hackathon 2026

---

## Prerequisites

Before testing, complete these setup steps:

1. **Follow** `docs/paypal-setup.md` to get PayPal Sandbox credentials
2. **Follow** `docs/openai-setup.md` to configure OpenAI (or enable Demo Mode)
3. **Configure** all three `.env` files (copy from `.env.example` in each folder)
4. **Ensure** MySQL 8.0.45 is running with database `paypilot_ai`

---

## Starting the Application

Open **three separate terminals**:

**Terminal 1 — Backend (Spring Boot)**
```bash
cd backend
mvn spring-boot:run
# Wait for: "Started PayPilotApplication in X seconds"
# Backend runs on http://localhost:8080
```

**Terminal 2 — AI Service (Node.js)**
```bash
cd ai-service
npm run dev
# Wait for: "PayPilot AI Service running on port 3001"
# AI service runs on http://localhost:3001 (internal only)
```

**Terminal 3 — Frontend (React)**
```bash
cd frontend
npm run dev
# Navigate to http://localhost:5173
```

---

## End-to-End Demo Walkthrough

### Demo 1 — Manual Invoice Creation and PayPal Payment

**Demonstrates**: PayPal Invoicing API + Orders API integration

1. **Register** a new account at `http://localhost:5173/register`
   - Use any email and password
   
2. **Create an invoice** at **Invoices → New Invoice**
   - Client name: `Acme Corporation`
   - Client email: your PayPal sandbox personal account email
   - Amount: `250.00`
   - Currency: `USD`
   - Due date: 30 days from today
   - Click **Create Invoice**

3. **Send the invoice via PayPal** — Click **Send via PayPal**
   - PayPilot creates a PayPal Sandbox invoice
   - Status changes to `SENT`
   - A PayPal payment link is generated

4. **Simulate payment** — Click the PayPal payment link
   - Log in as your Sandbox Personal (buyer) account
   - Review the invoice and click **Pay Now**
   - Complete the payment

5. **Verify payment captured** — Return to PayPilot AI
   - Invoice status should show: **PAID [SANDBOX] ✓**
   - Captured timestamp and PayPal capture ID are displayed
   - Audit log shows the full payment event chain

> **Compliance note**: The PAID status is set only after the PayPal API confirms `status: COMPLETED` in the capture response. It is never set without this verification.

---

### Demo 2 — AI Invoice Extraction

**Demonstrates**: GPT-4o invoice extraction agent

1. At **Invoices → Upload Invoice**, upload a PDF invoice
   - A sample PDF is provided in `docs/sample-invoice.pdf`
   
2. The AI service extracts:
   - Vendor name, invoice number, date, due date
   - Line items and amounts
   - Total amount and currency

3. The UI displays extraction results with:
   - **"⚠️ AI-extracted — please verify before saving"** label
   - Confidence score (e.g., 94%)
   - Editable fields for corrections

4. Click **Confirm and Save** to create the invoice
   - Your corrections override any AI errors
   - The AI extraction is logged in `ai_extraction_logs`

---

### Demo 3 — Agentic Payment Workflow (Human Approval Gate)

**Demonstrates**: AI Payment Operations Agent with mandatory human approval

1. Navigate to **AI Assistant** panel
2. Type: `"Pay the invoice for Acme Corporation"`
3. The AI identifies the invoice and proposes:
   ```
   📋 Payment Proposal (AI-generated — requires your approval)
   Invoice: #INV-001 — Acme Corporation
   Amount: $250.00 USD
   Action: Create PayPal payment order
   
   [Approve] [Reject]
   ```
4. Click **Approve** — Spring Boot creates the PayPal order and executes capture
5. The audit log records: `actor: AI_AGENT` → `actor: USER (approved)` → `SYSTEM (executed)`

> **Compliance note**: The AI never calls PayPal directly. It only proposes actions. All payment execution goes through Spring Boot after user approval.

---

### Demo 4 — Payment Reconciliation (Optional)

**Demonstrates**: AI reconciliation agent

1. Navigate to **Reports → Reconciliation**
2. Click **Run AI Reconciliation**
3. The AI analyzes your invoices and PayPal payment records
4. Report shows:
   - ✅ Matched: invoices with verified payments
   - ⚠️ Unmatched: invoices with no payment
   - ⚠️ Flagged: amount discrepancies

> **Label shown on report**: "AI analysis — verify with your own records"

---

## Verification Checklist for Judges

| Feature | How to verify |
|---------|--------------|
| PayPal Sandbox is real | Check the PayPal Developer Dashboard — the invoice appears there |
| PAID only on real capture | Try to find any PAID invoice without a capture ID in the DB |
| AI output is labeled | Every AI result has an AI-generated label in the UI |
| Human approval gate exists | No payment completes without clicking Approve |
| No credentials in code | `git grep -r "PAYPAL_CLIENT"` returns only `.env.example` |
| Audit log is complete | Query `SELECT * FROM audit_logs ORDER BY created_at DESC` |

---

## Demo Mode (No OpenAI Key)

If you don't have an OpenAI API key, set `DEMO_MODE=true` in `ai-service/.env`.

Demo Mode:
- Invoice extraction returns a pre-built structured response from `src/mock/`
- AI panel shows realistic example analysis
- All UI labels, approval gates, and audit logging work identically
- UI shows `[DEMO]` badge next to AI outputs

---

## Troubleshooting

| Problem | Solution |
|---------|---------|
| Backend won't start | Check `backend/.env` has all required vars; check MySQL is running |
| PayPal API error | Verify sandbox credentials; ensure `PAYPAL_MODE=sandbox` |
| AI extraction fails | Check `ai-service/.env` OPENAI_API_KEY; or enable DEMO_MODE |
| Invoice not marked PAID | Ensure PayPal capture completed; check audit_logs table |
| CORS error in browser | Ensure frontend uses `VITE_API_BASE_URL=http://localhost:8080/api` |

