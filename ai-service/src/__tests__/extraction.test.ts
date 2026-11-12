import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError, FinishReason } from '@google/genai';
import type { NextFunction, Request, Response } from 'express';
import {
  AiConfigurationError,
  AiRateLimitError,
  AiUpstreamError,
  DEFAULT_GEMINI_MODEL,
  extractInvoiceFromPrompt,
  resolveConfiguredGeminiModel,
  type GeminiGenerateClient,
  type GeminiGenerateResponse,
} from '../agents/invoiceExtractor.js';
import { validateServiceSecret } from '../middleware/auth.js';
import { createExtractionRouter } from '../routes/extraction.js';
import { validateAndEvaluateDraft } from '../validation/extractionSchema.js';

interface CapturedResponse {
  statusCode: number;
  body: unknown;
}

function createMockResponse(): { res: Response; captured: CapturedResponse } {
  const captured: CapturedResponse = { statusCode: 200, body: undefined };
  const res = {
    status(code: number) {
      captured.statusCode = code;
      return this;
    },
    json(payload: unknown) {
      captured.body = payload;
      return this;
    },
  } as unknown as Response;
  return { res, captured };
}

function createMockGeminiClient(
  reply: GeminiGenerateResponse | (() => Promise<GeminiGenerateResponse>)
): GeminiGenerateClient {
  return {
    models: {
      async generateContent() {
        return typeof reply === 'function' ? reply() : reply;
      },
    },
  };
}

async function invokeExtractionRoute(
  body: unknown,
  deps: Parameters<typeof createExtractionRouter>[0]
): Promise<CapturedResponse> {
  const router = createExtractionRouter(deps);
  const layer = router.stack.find((item) => item.route?.path === '/');
  assert.ok(layer?.route, 'Expected POST / route on extraction router');
  const handler = layer.route.stack[0].handle as (
    req: Request,
    res: Response,
    next: NextFunction
  ) => Promise<void>;

  const req = { body } as Request;
  const { res, captured } = createMockResponse();
  let forwardedError: unknown;
  await handler(req, res, (err?: unknown) => {
    forwardedError = err;
  });
  if (forwardedError) {
    throw forwardedError;
  }
  return captured;
}

test('validateServiceSecret blocks missing, wrong, or unconfigured shared secret', () => {
  const previousSecret = process.env['AI_SERVICE_SECRET'];
  try {
    delete process.env['AI_SERVICE_SECRET'];
    const { res: res503, captured: cap503 } = createMockResponse();
    let nextCalled = false;
    validateServiceSecret({ headers: {} } as Request, res503, () => {
      nextCalled = true;
    });
    assert.equal(cap503.statusCode, 503);
    assert.equal(nextCalled, false);

    process.env['AI_SERVICE_SECRET'] = 'test-shared-secret-value';

    const { res: resMissing, captured: capMissing } = createMockResponse();
    validateServiceSecret({ headers: {} } as Request, resMissing, () => {
      nextCalled = true;
    });
    assert.equal(capMissing.statusCode, 401);
    assert.equal(nextCalled, false);

    const { res: resWrong, captured: capWrong } = createMockResponse();
    validateServiceSecret(
      { headers: { 'x-ai-service-secret': 'wrong-secret' } } as unknown as Request,
      resWrong,
      () => {
        nextCalled = true;
      }
    );
    assert.equal(capWrong.statusCode, 401);
    assert.equal(nextCalled, false);

    const { res: resOk } = createMockResponse();
    validateServiceSecret(
      { headers: { 'x-ai-service-secret': 'test-shared-secret-value' } } as unknown as Request,
      resOk,
      () => {
        nextCalled = true;
      }
    );
    assert.equal(nextCalled, true);
  } finally {
    if (previousSecret === undefined) {
      delete process.env['AI_SERVICE_SECRET'];
    } else {
      process.env['AI_SERVICE_SECRET'] = previousSecret;
    }
  }
});

test('resolves GEMINI_MODEL from environment with safe free-tier default fallback', () => {
  const previousModel = process.env['GEMINI_MODEL'];
  try {
    delete process.env['GEMINI_MODEL'];
    assert.equal(resolveConfiguredGeminiModel(), DEFAULT_GEMINI_MODEL);

    process.env['GEMINI_MODEL'] = '  gemini-3.8-flash  ';
    assert.equal(resolveConfiguredGeminiModel(), 'gemini-3.8-flash');
    assert.equal(resolveConfiguredGeminiModel('gemini-3.5-flash-lite'), 'gemini-3.5-flash-lite');
  } finally {
    if (previousModel === undefined) {
      delete process.env['GEMINI_MODEL'];
    } else {
      process.env['GEMINI_MODEL'] = previousModel;
    }
  }
});

test('extracts partial draft via mocked Gemini SDK and flags missing clientEmail', async () => {
  let capturedModel: string | undefined;
  let capturedMimeType: string | undefined;
  const mockClient: GeminiGenerateClient = {
    models: {
      async generateContent(params) {
        capturedModel = params.model;
        capturedMimeType = params.config?.responseMimeType;
        return {
          text: JSON.stringify({
            clientName: 'Alex',
            clientEmail: null,
            description: 'Website development',
            amount: 750,
            currency: 'USD',
            dueDate: '2026-11-18',
            confidence: 0.94,
            assumptions: 'Interpreted "$" as USD and "in seven days" from 2026-11-11.',
          }),
          modelVersion: 'gemini-3.5-flash-lite',
          usageMetadata: {
            promptTokenCount: 140,
            candidatesTokenCount: 48,
          },
          candidates: [{ finishReason: FinishReason.STOP }],
        };
      },
    },
  };

  const result = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex for website development. Payment is due in seven days.',
      referenceDate: '2026-11-11',
    },
    { client: mockClient, demoMode: false, model: 'gemini-3.5-flash-lite' }
  );

  assert.equal(capturedModel, 'gemini-3.5-flash-lite');
  assert.equal(capturedMimeType, 'application/json');
  assert.equal(result.statusCode, 200);

  const body = result.body as Record<string, unknown>;
  assert.equal(body['aiGenerated'], true);
  assert.equal(body['requiresHumanReview'], true);
  assert.equal(body['sourceMode'], 'live');
  assert.equal(body['modelUsed'], 'gemini-3.5-flash-lite');
  assert.equal(body['readyToSave'], false);
  assert.deepEqual(body['missingFields'], ['clientEmail']);
  assert.deepEqual(body['validationErrors'], {});

  const draft = body['draft'] as Record<string, unknown>;
  assert.equal(draft['clientName'], 'Alex');
  assert.equal(draft['clientEmail'], null);
  assert.equal(draft['description'], 'Website development');
  assert.equal(draft['amount'], 750);
  assert.equal(draft['currency'], 'USD');
  assert.equal(draft['dueDate'], '2026-11-18');
  assert.deepEqual(body['tokenUsage'], { promptTokens: 140, completionTokens: 48 });
});

test('returns readyToSave=true when mocked Gemini extraction contains all valid fields', async () => {
  const mockClient = createMockGeminiClient({
    text: JSON.stringify({
      clientName: 'Alex Morgan',
      clientEmail: 'Alex@Example.COM',
      description: 'Website development',
      amount: 750.5,
      currency: 'usd',
      dueDate: '2026-11-18',
      confidence: 0.98,
      assumptions: null,
    }),
    modelVersion: 'gemini-3.5-flash-lite',
    usageMetadata: { promptTokenCount: 150, candidatesTokenCount: 55 },
  });

  const result = await invokeExtractionRoute(
    {
      prompt: 'Invoice Alex Morgan (alex@example.com) $750.50 for website development due 2026-11-18.',
      referenceDate: '2026-11-11',
    },
    { client: mockClient, demoMode: false }
  );

  assert.equal(result.statusCode, 200);
  const body = result.body as Record<string, unknown>;
  assert.equal(body['readyToSave'], true);
  assert.deepEqual(body['missingFields'], []);
  assert.deepEqual(body['validationErrors'], {});

  const draft = body['draft'] as Record<string, unknown>;
  assert.equal(draft['clientEmail'], 'alex@example.com');
  assert.equal(draft['currency'], 'USD');
});

test('rejects invalid input prompts with 400 Bad Request', async () => {
  const result = await invokeExtractionRoute(
    { prompt: 'hi' },
    { demoMode: true }
  );
  assert.equal(result.statusCode, 400);
});

test('rejects invalid JSON or malformed structured responses from Gemini with 502', async () => {
  const invalidJsonClient = createMockGeminiClient({
    text: '{invalid-json',
  });
  await assert.rejects(
    () =>
      extractInvoiceFromPrompt(
        'Create a $750 invoice for Alex for consulting.',
        '2026-11-11',
        { client: invalidJsonClient, demoMode: false }
      ),
    AiUpstreamError
  );
  const invalidJsonRoute = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex for consulting.',
      referenceDate: '2026-11-11',
    },
    { client: invalidJsonClient, demoMode: false }
  );
  assert.equal(invalidJsonRoute.statusCode, 502);

  const malformedSchemaClient = createMockGeminiClient({
    text: JSON.stringify({
      clientName: 'Alex',
      confidence: 2.5,
    }),
  });
  const malformedSchemaRoute = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex for consulting.',
      referenceDate: '2026-11-11',
    },
    { client: malformedSchemaClient, demoMode: false }
  );
  assert.equal(malformedSchemaRoute.statusCode, 502);

  const emptyTextClient = createMockGeminiClient({ text: '   ' });
  const emptyTextRoute = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex for consulting.',
      referenceDate: '2026-11-11',
    },
    { client: emptyTextClient, demoMode: false }
  );
  assert.equal(emptyTextRoute.statusCode, 502);
});

test('maps Gemini API quota/rate-limit errors cleanly without falling back to mock data', async () => {
  const rateLimitedClient = createMockGeminiClient(async () => {
    throw new ApiError({ status: 429, message: 'RESOURCE_EXHAUSTED: Free tier quota reached.' });
  });

  await assert.rejects(
    () =>
      extractInvoiceFromPrompt(
        'Create a $750 invoice for Alex for website development.',
        '2026-11-11',
        { client: rateLimitedClient, demoMode: false }
      ),
    AiRateLimitError
  );

  const rateLimitedRoute = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex for website development.',
      referenceDate: '2026-11-11',
    },
    { client: rateLimitedClient, demoMode: false }
  );
  assert.equal(rateLimitedRoute.statusCode, 429);

  const genericFailureClient = createMockGeminiClient(async () => {
    throw new ApiError({ status: 500, message: 'Internal Gemini error' });
  });
  const genericFailureRoute = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex for website development.',
      referenceDate: '2026-11-11',
    },
    { client: genericFailureClient, demoMode: false }
  );
  assert.equal(genericFailureRoute.statusCode, 502);
});

test('throws AiConfigurationError (503) when live mode lacks GEMINI_API_KEY', async () => {
  await assert.rejects(
    () =>
      extractInvoiceFromPrompt('Create a $750 invoice for Alex', '2026-11-11', {
        demoMode: false,
        apiKey: 'your_gemini_api_key_here',
      }),
    AiConfigurationError
  );

  const routeResult = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex',
      referenceDate: '2026-11-11',
    },
    { demoMode: false, apiKey: '' }
  );
  assert.equal(routeResult.statusCode, 503);
});

test('DEMO_MODE=true extracts fields deterministically without calling Gemini or inventing missing email', async () => {
  const result = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex for website development. Payment is due in seven days.',
      referenceDate: '2026-11-11',
    },
    { demoMode: true }
  );

  assert.equal(result.statusCode, 200);
  const body = result.body as Record<string, unknown>;
  assert.equal(body['sourceMode'], 'demo');
  assert.equal(body['modelUsed'], 'demo-mock-extractor');
  assert.equal(body['readyToSave'], false);
  assert.deepEqual(body['missingFields'], ['clientEmail']);

  const draft = body['draft'] as Record<string, unknown>;
  assert.equal(draft['clientName'], 'Alex');
  assert.equal(draft['amount'], 750);
  assert.equal(draft['currency'], 'USD');
  assert.equal(draft['dueDate'], '2026-11-18');
});

test('validateAndEvaluateDraft rejects invalid email, negative amount, invalid currency, and past date', () => {
  const evaluated = validateAndEvaluateDraft(
    {
      clientName: 'Alex',
      clientEmail: 'not-an-email',
      description: 'Consulting',
      amount: -50,
      currency: 'US',
      dueDate: '2026-11-01',
      confidence: 0.7,
      assumptions: null,
    },
    '2026-11-11'
  );

  assert.equal(evaluated.readyToSave, false);
  assert.deepEqual(evaluated.missingFields, []);
  assert.ok(evaluated.validationErrors['clientEmail']);
  assert.ok(evaluated.validationErrors['amount']);
  assert.ok(evaluated.validationErrors['currency']);
  assert.ok(evaluated.validationErrors['dueDate']);
});
