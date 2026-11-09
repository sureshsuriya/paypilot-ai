/**
 * Service secret authentication middleware.
 * Validates that requests come from the Spring Boot backend only.
 * The AI service is never called directly from the browser.
 */
import type { Request, Response, NextFunction } from 'express';

export function validateServiceSecret(
  req: Request,
  res: Response,
  next: NextFunction
): void {
  const secret = process.env['AI_SERVICE_SECRET'];
  const provided = req.headers['x-ai-service-secret'];

  if (!secret) {
    console.error('AI_SERVICE_SECRET is not configured. Rejecting all requests.');
    res.status(503).json({ error: 'Service not configured' });
    return;
  }

  if (!provided || provided !== secret) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }

  next();
}

