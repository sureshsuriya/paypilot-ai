# PayPilot AI — API Reference
## Backend: http://localhost:8080/api

> **Status**: Placeholder — endpoints added as each phase is implemented.  
> All endpoints require `Authorization: Bearer <JWT>` unless marked Public.

---

## Authentication

| Method | Endpoint | Auth | Description |
|--------|---------|------|-------------|
| POST | `/api/auth/register` | Public | Register new user |
| POST | `/api/auth/login` | Public | Login, returns JWT |

---

## Invoices

| Method | Endpoint | Auth | Description |
|--------|---------|------|-------------|
| GET | `/api/invoices` | Required | List user's invoices |
| GET | `/api/invoices/{id}` | Required | Get invoice by ID |
| POST | `/api/invoices` | Required | Create invoice |
| PUT | `/api/invoices/{id}` | Required | Update invoice |
| DELETE | `/api/invoices/{id}` | Required | Delete draft invoice |
| POST | `/api/invoices/{id}/send` | Required | Send invoice via PayPal |
| POST | `/api/invoices/upload` | Required | Upload PDF for AI extraction |

---

## Payments (PayPal)

| Method | Endpoint | Auth | Description |
|--------|---------|------|-------------|
| POST | `/api/payments/create-order/{invoiceId}` | Required | Create PayPal order |
| POST | `/api/payments/capture/{orderId}` | Required | Capture PayPal order |
| GET | `/api/payments/{id}` | Required | Get payment details |
| POST | `/api/webhooks/paypal` | Public (signature verified) | PayPal webhook handler |

---

## AI (Proxied through backend — browser never contacts AI service directly)

| Method | Endpoint | Auth | Description |
|--------|---------|------|-------------|
| POST | `/api/ai/extract` | Required | Extract invoice from PDF |
| POST | `/api/ai/reconcile` | Required | Run reconciliation report |
| POST | `/api/ai/propose-payment` | Required | AI payment proposal (returns for human approval) |
| POST | `/api/ai/explain` | Required | Financial explanation query |

---

## System

| Method | Endpoint | Auth | Description |
|--------|---------|------|-------------|
| GET | `/api/health` | Public | Backend health check |
| GET | `/api/paypal/status` | Public | PayPal connectivity check |

---

## AI Service Internal Health (not browser-accessible)

| Method | Endpoint | Auth | Description |
|--------|---------|------|-------------|
| GET | `/health` | None | AI service health (server-to-server only) |

