# PayPilot AI — PayPal Sandbox Setup Guide
## For Judges and Developers

---

## Overview

PayPilot AI uses **PayPal Developer Sandbox REST API (Orders v2)** exclusively. All transactions are test transactions with zero real money movement. The Spring Boot backend enforces `PAYPAL_MODE=sandbox` and `https://api-m.sandbox.paypal.com` fail-closed at startup and before every API request.

---

## Step 1 — Create a PayPal Developer Account

1. Go to [https://developer.paypal.com](https://developer.paypal.com)
2. Click **Log in to Dashboard** (or **Sign Up** to create a free developer account).

---

## Step 2 — Create a Sandbox REST Application

1. In the PayPal Developer Dashboard, open **Apps & Credentials**.
2. Confirm you are on the **Sandbox** tab (not Live).
3. Click **Create App**, enter a name (e.g., `PayPilot AI`), select **Merchant**, and click **Create App**.
4. Copy your Sandbox **Client ID** and **Client Secret**.

---

## Step 3 — Configure Backend Sandbox Credentials

Copy `backend/.env.example` to `backend/.env` and set:

```env
PAYPAL_CLIENT_ID=your_sandbox_client_id_here
PAYPAL_CLIENT_SECRET=your_sandbox_client_secret_here
PAYPAL_MODE=sandbox
PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com
```

> **Security guarantees**:
> - Credentials exist **only** in `backend/.env` (excluded by `.gitignore`) and are never exposed to React, `ai-service`, API responses, or audit logs.
> - Changing `PAYPAL_MODE` or `PAYPAL_BASE_URL` away from PayPal Sandbox triggers `PayPalConfigurationException`.

---

## Step 4 — Locate Your Sandbox Test Accounts

In the PayPal Developer Dashboard, go to **Sandbox** → **Accounts**:
- **Business account**: Merchant account associated with your Sandbox REST app (receives sandbox test payments).
- **Personal account**: Buyer test account used to sign in on PayPal Sandbox Checkout and approve a test payment.

---

## Step 5 — Verify Backend Readiness

Start MySQL (`paypilot_ai`) and the Spring Boot backend (`cd backend && mvn spring-boot:run`), then check:

```http
GET http://localhost:8080/api/health
```

Expected response (`200 OK`):

```json
{
  "status": "ok",
  "timestamp": "...",
  "service": "paypilot-backend"
}
```

---

## Step 6 — End-to-End Sandbox Payment Flow (Orders v2)

1. **Draft & Approve (Seller Portal)**:
   - Create an invoice manually or via Gemini AI at `/invoices/create`.
   - Open the invoice at `/invoices/:id`, inspect the SHA-256 snapshot hash, and click **Approve Invoice**.
2. **Generate Customer Payment Link**:
   - In **Customer Payment Link & Delivery**, select an expiration window and click **Generate Payment Link** (`/pay/:token`).
3. **Customer Checkout (`/pay/:token`)**:
   - Open the link in a clean/incognito browser window and click **Pay with PayPal (Sandbox)**.
   - Spring Boot (`POST /api/public/pay/{token}/orders`) acquires an atomic invoice lease, verifies the approved snapshot, creates a PayPal Orders v2 order (`intent=CAPTURE`), and returns a validated `sandbox.paypal.com` approval URL.
4. **Buyer Approval & Return Capture**:
   - Sign in with your **Sandbox Personal (Buyer)** account and complete checkout.
   - Upon redirect back to `/pay/:token?paypal=return&token=...`, the frontend calls `POST /api/public/pay/{token}/capture`.
   - Spring Boot captures the PayPal order, verifies `status = COMPLETED`, `captureId`, `amount`, and `currency`, saves `PaymentStatus.CAPTURED`, and transitions the invoice to `PAID`.
5. **Interrupted or Pending Payments**:
   - If capture returns `PENDING` or a network timeout occurs, PayPilot preserves the order idempotently and lets the seller or customer click **Check Payment Status** (querying PayPal `GET /v2/checkout/orders/{id}` safely without creating duplicate charges).

---

## Troubleshooting

| Issue | Resolution |
| --- | --- |
| `401 Unauthorized` / auth error from PayPal | Verify `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` are copied from the **Sandbox** app tab (not Live). |
| Checkout redirect rejected | PayPilot blocks non-Sandbox or non-HTTPS approval URLs; verify `PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com`. |
| Link shows `REVOKED` or `EXPIRED` | Generate a fresh link from `/invoices/:id` (single-active link design revokes prior tokens). |
| Payment remains `APPROVED` / `PENDING` | Click **Check Payment Status** in Seller **Payments** (`/payments`) or Customer Portal to query PayPal capture status. |

---

## Sandbox vs. Live Reference

| Feature | Sandbox (This App) | Live |
| --- | --- | --- |
| Real money | No (test only) | Yes |
| Credentials | Sandbox Client ID & Secret | Live Client ID & Secret |
| API Base URL | `https://api-m.sandbox.paypal.com` | `https://api-m.paypal.com` |
| UI Indicator | `[SANDBOX]` badge shown across both portals | — |

See the official [PayPal Sandbox documentation](https://developer.paypal.com/tools/sandbox/) for managing test accounts.
