/**
 * PayPilot AI — Express App Factory
 * Separates HTTP routing configuration from process startup so tests can mount
 * the app cleanly with injected mock extraction dependencies.
 */
import express from 'express';
import { validateServiceSecret } from './middleware/auth.js';
import { errorHandler } from './middleware/errorHandler.js';
import { healthRouter } from './routes/health.js';
import { createExtractionRouter } from './routes/extraction.js';
import { reconciliationRouter } from './routes/reconciliation.js';
import { paymentOpsRouter } from './routes/paymentOps.js';
import { explainerRouter } from './routes/explainer.js';
import type { ExtractionDependencies } from './agents/invoiceExtractor.js';

export interface AppOptions {
  extractionDeps?: ExtractionDependencies;
}

export function createApp(options: AppOptions = {}) {
  const app = express();

  app.use(express.json({ limit: '10mb' }));
  app.use(express.urlencoded({ extended: true }));

  // Unauthenticated readiness probe for Spring Boot
  app.use('/health', healthRouter);

  // All AI routes require the backend shared secret
  app.use(validateServiceSecret);

  app.use('/api/ai/extract', createExtractionRouter(options.extractionDeps));
  app.use('/api/ai/reconcile', reconciliationRouter);
  app.use('/api/ai/payment-ops', paymentOpsRouter);
  app.use('/api/ai/explain', explainerRouter);

  app.use(errorHandler);

  return app;
}

