/**
 * Payment operations route stub.
 * Phase 6 implementation: AI proposes PayPal action → Spring Boot executes after human approval.
 * The AI service NEVER calls PayPal directly.
 */
import { Router } from 'express';

export const paymentOpsRouter = Router();

paymentOpsRouter.post('/', (_req, res) => {
  res.status(501).json({ message: 'Payment operations agent — implemented in Phase 6' });
});

