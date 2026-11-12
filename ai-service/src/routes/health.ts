/**
 * Health check route — unauthenticated.
 * Used by Spring Boot to verify the AI service is running before proxying requests.
 */
import { Router } from 'express';
import { resolveConfiguredGeminiModel } from '../agents/invoiceExtractor.js';

export const healthRouter = Router();

healthRouter.get('/', (_req, res) => {
  const demoMode = process.env['DEMO_MODE'] === 'true';
  res.json({
    status: 'ok',
    service: 'paypilot-ai-service',
    mode: demoMode ? 'demo' : 'live',
    model: demoMode ? 'demo-mock-extractor' : resolveConfiguredGeminiModel(),
    timestamp: new Date().toISOString(),
  });
});
