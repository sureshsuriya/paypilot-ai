/**
 * PayPilot AI — AI Orchestration Service Entry Point
 *
 * ARCHITECTURE NOTE:
 *   This service is INTERNAL ONLY.
 *   It is called exclusively by the Spring Boot backend via authenticated
 *   server-to-server requests. It is NEVER exposed directly to the browser.
 *
 * SAFETY RULES:
 *   - This service does NOT call PayPal APIs. All payment operations go through Spring Boot.
 *   - All AI outputs are validated with Zod before being returned.
 *   - Financial figures come from the database (via Spring Boot), never from AI.
 *   - Human approval is required before any payment-related action.
 *
 * Node.js 22.12.0 | TypeScript 5.x | Express 4.x | OpenAI SDK 4.x
 */

import 'dotenv/config';
import express from 'express';
import { validateServiceSecret } from './middleware/auth.js';
import { errorHandler } from './middleware/errorHandler.js';
import { healthRouter } from './routes/health.js';
import { extractionRouter } from './routes/extraction.js';
import { reconciliationRouter } from './routes/reconciliation.js';
import { paymentOpsRouter } from './routes/paymentOps.js';
import { explainerRouter } from './routes/explainer.js';

const app = express();
const PORT = parseInt(process.env['AI_SERVICE_PORT'] ?? '3001', 10);

// ---- Middleware ----
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

// ---- Health check (unauthenticated — for Spring Boot readiness checks) ----
app.use('/health', healthRouter);

// ---- All other routes require the shared backend secret ----
app.use(validateServiceSecret);

// ---- AI Agent Routes ----
// All routes are prefixed /api/ai/* when called from Spring Boot
app.use('/api/ai/extract', extractionRouter);       // Invoice PDF extraction agent
app.use('/api/ai/reconcile', reconciliationRouter); // Payment reconciliation agent
app.use('/api/ai/payment-ops', paymentOpsRouter);   // Payment operations agent (propose only)
app.use('/api/ai/explain', explainerRouter);         // Financial explanation agent

// ---- Error handler (must be last) ----
app.use(errorHandler);

app.listen(PORT, () => {
  const demoMode = process.env['DEMO_MODE'] === 'true';
  console.log(`PayPilot AI Service running on port ${PORT}`);
  console.log(`Mode: ${demoMode ? '⚠️  DEMO (mock AI responses)' : '✅ LIVE (OpenAI GPT-4o)'}`);
  console.log(`Model: ${process.env['OPENAI_MODEL'] ?? 'gpt-4o'}`);
  console.log('Internal service — not exposed to browser.');
});

export default app;

