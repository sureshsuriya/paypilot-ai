/**
 * Global error handler middleware.
 * Returns structured error responses; never leaks stack traces in production.
 */
import type { Request, Response, NextFunction } from 'express';

export function errorHandler(
  err: Error,
  _req: Request,
  res: Response,
  _next: NextFunction
): void {
  console.error('[AI Service Error]', err.message);

  const isDev = process.env['NODE_ENV'] === 'development';

  res.status(500).json({
    error: 'Internal AI service error',
    message: isDev ? err.message : 'An unexpected error occurred',
  });
}

