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
 * Node.js 22.12.0 | TypeScript 5.x | Express 4.x | @google/genai
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import dotenv from 'dotenv';
import { createApp } from './app.js';
import {
  isGeminiApiKeyConfigured,
  resolveConfiguredGeminiModel,
} from './agents/invoiceExtractor.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
dotenv.config({ path: path.resolve(__dirname, '../.env') });

const app = createApp();
const PORT = parseInt(process.env['AI_SERVICE_PORT'] ?? '3001', 10);

const demoMode = process.env['DEMO_MODE'] === 'true';
const hasApiKey = isGeminiApiKeyConfigured();
const configuredModel = resolveConfiguredGeminiModel();

if (!demoMode && !hasApiKey) {
  console.warn('⚠️  DEMO_MODE is false and GEMINI_API_KEY is missing or placeholder.');
}

app.listen(PORT, () => {
  console.log(`PayPilot AI Service running on port ${PORT}`);
  console.log(`Mode: ${demoMode ? '⚠️  DEMO (mock AI responses)' : `✅ LIVE (Google Gemini: ${configuredModel})`}`);
  console.log(`Model: ${configuredModel}`);
  console.log('Internal service — not exposed to browser.');
});

export default app;
