/**
 * Invoice extraction route stub.
 * Phase 5 implementation: PDF text extraction → GPT-4o structured output → Zod validation.
 */
import { Router } from 'express';

export const extractionRouter = Router();

extractionRouter.post('/', (_req, res) => {
  res.status(501).json({ message: 'Invoice extraction — implemented in Phase 5' });
});

