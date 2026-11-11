import assert from 'node:assert/strict';
import test from 'node:test';
import type { NextFunction, Request, Response } from 'express';
import {
  AiConfigurationError,
  AiUpstreamError,
  extractInvoiceFromPrompt,
  type OpenAiParseClient,
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

test('extracts partial draft via mocked OpenAI SDK and flags missing clientEmail', async () => {
  const mockClient: OpenAiParseClient = {
    beta: {
      chat: {
        completions: {
          async parse() {
            return {
              model: 'gpt-4o',
              usage: { prompt_tokens: 140, completion_tokens: 48 },
              choices: [
                {
                  message: {
                    parsed: {
                      clientName: 'Alex',
                      clientEmail: null,
                      description: 'Website development',
                      amount: 750,
                      currency: 'USD',
                      dueDate: '2026-11-18',
                      confidence: 0.94,
                      assumptions: 'Interpreted "$" as USD and "in seven days" from 2026-11-11.',
                    },
                    refusal: null,
                  },
                },
              ],
            };
          },
        },
      },
    },
  };

  const result = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex for website development. Payment is due in seven days.',
      referenceDate: '2026-11-11',
    },
    { client: mockClient, demoMode: false, model: 'gpt-4o' }
  );

  assert.equal(result.statusCode, 200);
  const body = result.body as Record<string, unknown>;
  assert.equal(body['aiGenerated'], true);
  assert.equal(body['requiresHumanReview'], true);
  assert.equal(body['sourceMode'], 'live');
  assert.equal(body['modelUsed'], 'gpt-4o');
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
});

test('returns readyToSave=true when mocked OpenAI extraction contains all valid fields', async () => {
  const mockClient: OpenAiParseClient = {
    beta: {
      chat: {
        completions: {
          async parse() {
            return {
              model: 'gpt-4o',
              usage: { prompt_tokens: 150, completion_tokens: 55 },
              choices: [
                {
                  message: {
                    parsed: {
                      clientName: 'Alex Morgan',
                      clientEmail: 'Alex@Example.COM',
                      description: 'Website development',
                      amount: 750.5,
                      currency: 'usd',
                      dueDate: '2026-11-18',
                      confidence: 0.98,
                      assumptions: null,
                    },
                    refusal: null,
                  },
                },
              ],
            };
          },
        },
      },
    },
  };

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

test('does not silently fall back to mock data when OpenAI fails in live mode', async () => {
  const failingClient: OpenAiParseClient = {
    beta: {
      chat: {
        completions: {
          async parse() {
            throw new Error('Simulated OpenAI network error');
          },
        },
      },
    },
  };

  await assert.rejects(
    () =>
      extractInvoiceFromPrompt(
        'Create a $750 invoice for Alex for website development.',
        '2026-11-11',
        { client: failingClient, demoMode: false }
      ),
    AiUpstreamError
  );

  const routeResult = await invokeExtractionRoute(
    {
      prompt: 'Create a $750 invoice for Alex for website development.',
      referenceDate: '2026-11-11',
    },
    { client: failingClient, demoMode: false }
  );
  assert.equal(routeResult.statusCode, 502);
});

test('throws AiConfigurationError (503) when live mode lacks API key', async () => {
  await assert.rejects(
    () =>
      extractInvoiceFromPrompt('Create a $750 invoice for Alex', '2026-11-11', {
        demoMode: false,
        apiKey: 'your_openai_api_key_here',
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

test('DEMO_MODE=true extracts fields deterministically without calling OpenAI or inventing missing email', async () => {
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
