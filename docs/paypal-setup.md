# PayPilot AI — PayPal Sandbox Setup Guide
## For Judges and Developers

---

## Overview

PayPilot AI uses **PayPal Sandbox** exclusively. All transactions shown in the application are test transactions and involve no real money. This guide explains how to set up PayPal Sandbox credentials and test accounts.

---

## Step 1 — Create a PayPal Developer Account

1. Go to [https://developer.paypal.com](https://developer.paypal.com)
2. Click **Log in to Dashboard** (top right)
3. If you don't have a PayPal account, click **Sign Up** and create one (free)
4. After logging in, you will be on the **PayPal Developer Dashboard**

---

## Step 2 — Create a Sandbox Application

1. In the Developer Dashboard, click **Apps & Credentials** in the left sidebar
2. Make sure you are on the **Sandbox** tab (not Live)
3. Click **Create App**
4. Enter an app name (e.g., `PayPilot AI`)
5. App type: **Merchant**
6. Click **Create App**

You will now see your app's **Client ID** and **Secret**.

---

## Step 3 — Copy Your Sandbox Credentials

From the app page:
- Copy the **Client ID** → paste into `backend/.env` as `PAYPAL_CLIENT_ID`
- Click **Show** next to Secret → copy → paste into `backend/.env` as `PAYPAL_CLIENT_SECRET`

Ensure these lines in `backend/.env`:
```
PAYPAL_CLIENT_ID=AaBbCcDd...your_actual_sandbox_client_id
PAYPAL_CLIENT_SECRET=your_actual_sandbox_secret
PAYPAL_MODE=sandbox
PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com
```

> ⚠️ **Never use Live credentials in this application.** The sandbox URL is different from live. `PAYPAL_MODE=sandbox` is enforced by the backend.

---

## Step 4 — Locate Your Sandbox Test Accounts

PayPal automatically creates sandbox test accounts for you.

1. In the Developer Dashboard, click **Sandbox** → **Accounts**
2. You will see two default accounts:
   - **Business account** (your merchant — receives payments)
   - **Personal account** (your buyer — makes payments)

Note these emails and passwords. You will use the **Personal account** to simulate a buyer completing payment in the checkout flow.

---

## Step 5 — Configure a Webhook (Optional for Judge Testing)

Webhooks allow PayPal to notify your app when a payment is captured. For local testing, you can use a tool like [ngrok](https://ngrok.com) to expose localhost.

1. In your app settings, click **Webhooks** → **Add Webhook**
2. Set the URL to your backend webhook endpoint: `https://your-ngrok-url/api/webhooks/paypal`
3. Subscribe to event: `PAYMENT.CAPTURE.COMPLETED`
4. Copy the **Webhook ID** → paste into `backend/.env` as `PAYPAL_WEBHOOK_ID`

**Note**: Webhook setup is optional for the basic demo. The application polls PayPal order status as a fallback.

---

## Step 6 — Verify the Configuration

After setting environment variables and starting the backend:

```bash
cd backend
mvn spring-boot:run
```

You should see startup logs without PayPal credential errors.

Test PayPal connectivity:
```
GET http://localhost:8080/api/paypal/status
```

Expected response:
```json
{
  "connected": true,
  "mode": "sandbox",
  "timestamp": "2026-11-10T..."
}
```

---

## Sandbox Testing Flow

### Full Payment Test:

1. Create an invoice in PayPilot AI
2. Click **Send via PayPal** — this creates a PayPal Sandbox invoice
3. Open the payment link that PayPal returns
4. Log in as your **Sandbox Personal (buyer) account**
5. Complete the payment
6. Return to PayPilot AI — the invoice should show status **PAID [SANDBOX]**

### Common Sandbox Issues:

| Issue | Solution |
|-------|---------|
| "Invalid credentials" error | Double-check Client ID and Secret are from Sandbox tab, not Live |
| Payment link not working | Ensure the sandbox buyer account is logged in |
| Status not updating | Webhook not configured; app will poll and update on next refresh |
| "Duplicate order" error | Use a unique invoice number for each test |

---

## Sandbox vs. Live Reminder

| Feature | Sandbox (This App) | Live |
|---------|--------------------|------|
| Money moves | ❌ No real money | ✅ Real money |
| Credentials | Sandbox Client ID/Secret | Live Client ID/Secret |
| Base URL | `api-m.sandbox.paypal.com` | `api-m.paypal.com` |
| Transactions labeled | ✅ [SANDBOX] badge | — |

**This application will never be configured for live payments without explicit code changes and live credential setup.**

---

*For questions about PayPal Sandbox, see: https://developer.paypal.com/tools/sandbox/*

