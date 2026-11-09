/**
 * Financial explanation route stub.
 * Phase 8 implementation: plain-language summaries from verified DB data.
 */
import { Router } from 'express';

export const explainerRouter = Router();

explainerRouter.post('/', (_req, res) => {
  res.status(501).json({ message: 'Financial explanation agent — implemented in Phase 8' });
});

