/**
 * Natural-language invoice extraction route.
 * Accepts plain-English requests from Spring Boot, calls Gemini structured JSON output,
 * validates with Zod, and returns a draft preview requiring human confirmation.
 */
import { Router, type Request, type Response, type NextFunction } from 'express';
import {
  ExtractInvoiceInputSchema,
  validateAndEvaluateDraft,
} from '../validation/extractionSchema.js';
import {
  AiConfigurationError,
  AiRateLimitError,
  AiUpstreamError,
  extractInvoiceFromPrompt,
  type ExtractionDependencies,
} from '../agents/invoiceExtractor.js';

export function createExtractionRouter(deps: ExtractionDependencies = {}): Router {
  const router = Router();

  router.post('/', async (req: Request, res: Response, next: NextFunction) => {
    const parsedInput = ExtractInvoiceInputSchema.safeParse(req.body);
    if (!parsedInput.success) {
      res.status(400).json({
        error: 'Validation failed',
        details: parsedInput.error.flatten().fieldErrors,
      });
      return;
    }

    const referenceDate =
      parsedInput.data.referenceDate ?? new Date().toISOString().slice(0, 10);

    try {
      const outcome = await extractInvoiceFromPrompt(
        parsedInput.data.prompt,
        referenceDate,
        deps
      );
      const evaluated = validateAndEvaluateDraft(outcome.parsed, referenceDate);

      res.json({
        aiGenerated: true,
        requiresHumanReview: true,
        label: 'AI-extracted draft — please verify all details before saving',
        sourceMode: outcome.sourceMode,
        modelUsed: outcome.modelUsed,
        confidence: outcome.parsed.confidence,
        assumptions: outcome.parsed.assumptions,
        draft: evaluated.draft,
        missingFields: evaluated.missingFields,
        validationErrors: evaluated.validationErrors,
        readyToSave: evaluated.readyToSave,
        tokenUsage: outcome.tokenUsage,
      });
    } catch (err) {
      if (err instanceof AiConfigurationError) {
        res.status(503).json({
          error: 'AI service unavailable',
          message: err.message,
        });
        return;
      }
      if (err instanceof AiRateLimitError) {
        res.status(429).json({
          error: 'AI quota or rate limit exceeded',
          message: err.message,
        });
        return;
      }
      if (err instanceof AiUpstreamError) {
        res.status(502).json({
          error: 'AI extraction failed',
          message: 'Unable to extract invoice fields from the Gemini provider.',
        });
        return;
      }
      next(err);
    }
  });

  return router;
}

export const extractionRouter = createExtractionRouter();
