/**
 * Health check route — unauthenticated.
 * Used by Spring Boot to verify the AI service is running before proxying requests.
 */
import { Router } from 'express';

export const healthRouter = Router();

healthRouter.get('/', (_req, res) => {
  const demoMode = process.env['DEMO_MODE'] === 'true';
  res.json({
    status: 'ok',
    service: 'paypilot-ai-service',
    mode: demoMode ? 'demo' : 'live',
    model: process.env['OPENAI_MODEL'] ?? 'gpt-4o',
    timestamp: new Date().toISOString(),
  });
});

