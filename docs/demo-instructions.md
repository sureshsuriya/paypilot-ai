# PayPilot AI — Demo & Judge Walkthrough

## 1. Prerequisites

1. MySQL 8.0+ running with `paypilot_ai` database configured in `backend/.env`.
2. PayPal Sandbox REST credentials configured in `backend/.env` (`PAYPAL_MODE=sandbox`).
3. `GEMINI_API_KEY` set in `ai-service/.env` (or `DEMO_MODE=true` for offline deterministic extraction).
4. Optional SMTP credentials (`MAIL_ENABLED=true`, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`) in `backend/.env` if testing live email dispatch.
5. Start all three services:
   - `cd backend && mvn spring-boot:run` (`http://localhost:8080`)
   - `cd ai-service && npm run dev` (`http://localhost:3001`)
   - `cd frontend && npm run dev` (`http://localhost:5173`)

---

## 2. End-to-End Two-Portal Demo Flow

### Step A — Seller Portal: Create & Approve Invoice
1. Open `http://localhost:5173/register` and create a Seller account (or sign in at `/login`).
2. Visit **Create Invoice** (`/invoices/create`):
   - **AI-Assisted Draft**: Enter `"Create a $750 invoice for Alex for website development. Payment is due in seven days."` and click **Extract Invoice Draft**.
   - Fill any missing fields (e.g., `clientEmail` and optional international phone like `+14155552671`), check **Human confirmation required**, and click **Confirm & Save Draft Invoice**.
3. On the Invoice Detail page (`/invoices/:id`):
   - Review the SHA-256 Snapshot Hash, click **Approve Invoice**, and confirm explicit approval.
   - Invoice status changes from `DRAFT` to `APPROVED`.

### Step B — Payment Link, Customers, Email & WhatsApp
1. Under **Customer Payment Link & Delivery**, choose an expiry window (e.g., `14 days`) and click **Generate Payment Link**.
   - Observe the masked prefix (`ppl_...`), copyable `/pay/:token` URL, and view counter.
   - Only one active link exists at a time; regenerating revokes the previous link automatically.
2. Click **Send Email** to preview the server-rendered responsive HTML / plain-text email (works even before SMTP is enabled; if `MAIL_ENABLED=true`, click **Send Invoice Email** to dispatch via SMTP and verify the `Email Delivery History` log).
3. Click **WhatsApp**:
   - Validate or add an international E.164 phone number (`+14155552671`). Saving via **Save Phone to Customer Record** updates both `/customers` and non-paid invoices without invalidating `APPROVED` snapshots.
   - Review the formatted WhatsApp message and click **Open WhatsApp to Send** (opens `wa.me` draft for manual send confirmation) or **Copy Message**.
4. Visit **Customers** (`/customers`) to inspect the seller-isolated directory, search customers, or click **Missing Phone** to add contact info.

### Step C — Separate Customer Payment Portal (`/pay/:token`)
1. Copy the `/pay/:token` URL and open it in an Incognito/Private browser window (zero seller JWT, zero seller sidebar/links).
2. Review invoice details: Merchant display name, Invoice number, Description, Amount & currency, Due date, and Sandbox mode notice.
3. Click **Pay with PayPal (Sandbox)** to redirect to PayPal Sandbox Checkout, log in with your **Sandbox Personal (Buyer)** test account, and approve payment.
4. Upon return to `/pay/:token`:
   - Backend calls PayPal Orders v2 capture & verifies amount, currency, order ID, and `COMPLETED` status.
   - Only after backend verification does the Customer Portal render **Payment Confirmed** with printable receipt details (`paypalOrderId`, `paypalCaptureId`, timestamp).
5. Refresh the Seller Portal **Dashboard** (`/dashboard`) and **Payments** (`/payments`) to observe updated **Paid Invoices**, **Verified Collected Revenue**, **Customer Link** provenance, and full payment audit history.

### Step D — Security & Edge Cases to Verify
- Revoking a link in Seller Portal causes `/pay/:token` in another browser to show **Payment link revoked** with zero invoice details leaked (`410 Gone` on checkout API calls).
- Seller Portal registration/login has no effect on `/pay/:token` (`publicApiClient` never attaches `Authorization` headers).
- Querying `SELECT id, invoice_number, status FROM invoices` & `SELECT paypal_order_id, paypal_capture_id, status FROM payments` confirms `PAID` invoices always pair with a verified `CAPTURED` payment.
