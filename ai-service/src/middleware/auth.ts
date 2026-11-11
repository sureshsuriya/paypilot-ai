/**
 * Service secret authentication middleware.
 * Validates that requests come from the Spring Boot backend only.
 * The AI service is never called directly from the browser.
 */
import crypto from 'node:crypto';
import type { Request, Response, NextFunction } from 'express';

export function validateServiceSecret(
  req: Request,
  res: Response,
  next: NextFunction
): void {
  const secret = process.env['AI_SERVICE_SECRET'];
  const headerValue = req.headers['x-ai-service-secret'];
  const provided = Array.isArray(headerValue) ? headerValue[0] : headerValue;

  if (!secret || secret === 'replace_with_shared_secret_here') {
    console.error('AI_SERVICE_SECRET is not configured. Rejecting all requests.');
    res.status(503).json({ error: 'Service not configured' });
    return;
  }

  if (!provided || !timingSafeEqualString(provided, secret)) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }

  next();
}

function timingSafeEqualString(a: string, b: string): boolean {
  const bufA = Buffer.from(a, 'utf8');
  const bufB = Buffer.from(b, 'utf8');
  if (bufA.length !== bufB.length) {
    return false;
  }
  return crypto.timingSafeEqual(bufA, bufB);
}

