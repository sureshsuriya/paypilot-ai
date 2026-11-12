import {
  ApiError,
  FinishReason,
  GoogleGenAI,
  Type,
  type GenerateContentParameters,
  type GenerateContentResponse,
  type Schema,
} from '@google/genai';
import {
  AiInvoiceExtractionSchema,
  type AiInvoiceExtractionResult,
} from '../validation/extractionSchema.js';
import { extractInvoiceInDemoMode } from '../mock/mockExtractor.js';

export const DEFAULT_GEMINI_MODEL = 'gemini-3.5-flash-lite';
const GEMINI_PLACEHOLDER_KEY = 'your_gemini_api_key_here';

export class AiConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AiConfigurationError';
  }
}

export class AiUpstreamError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AiUpstreamError';
  }
}

export class AiRateLimitError extends AiUpstreamError {
  constructor(message: string) {
    super(message);
    this.name = 'AiRateLimitError';
  }
}

export interface TokenUsage {
  promptTokens: number | null;
  completionTokens: number | null;
}

export interface InvoiceExtractionOutcome {
  parsed: AiInvoiceExtractionResult;
  sourceMode: 'live' | 'demo';
  modelUsed: string;
  tokenUsage: TokenUsage;
}

export type GeminiGenerateResponse = Pick<
  GenerateContentResponse,
  'text' | 'modelVersion' | 'usageMetadata' | 'candidates' | 'promptFeedback'
>;

/**
 * Minimal subset of @google/genai GoogleGenAI client used by the extractor so tests can
 * inject a deterministic mock without calling Google Gemini APIs or incurring quota usage.
 */
export interface GeminiGenerateClient {
  models: {
    generateContent(params: GenerateContentParameters): Promise<GeminiGenerateResponse>;
  };
}

export interface ExtractionDependencies {
  client?: GeminiGenerateClient;
  demoMode?: boolean;
  model?: string;
  apiKey?: string;
}

/**
 * Strict Gemini structured output Schema guaranteeing JSON adherence without
 * inventing missing client or financial fields.
 */
export const GEMINI_INVOICE_RESPONSE_SCHEMA: Schema = {
  type: Type.OBJECT,
  properties: {
    clientName: {
      type: Type.STRING,
      nullable: true,
      description: 'Client person or company name stated in the prompt, or null if omitted.',
    },
    clientEmail: {
      type: Type.STRING,
      nullable: true,
      description: 'Client email address stated in the prompt, or null if omitted.',
    },
    description: {
      type: Type.STRING,
      nullable: true,
      description: 'Service or product description stated in the prompt, or null if omitted.',
    },
    amount: {
      type: Type.NUMBER,
      nullable: true,
      description: 'Monetary invoice amount as a number, or null if omitted.',
    },
    currency: {
      type: Type.STRING,
      nullable: true,
      description: '3-letter uppercase ISO currency code (e.g. USD), or null if omitted.',
    },
    dueDate: {
      type: Type.STRING,
      nullable: true,
      description: 'Payment due date in YYYY-MM-DD format computed from referenceDate, or null if omitted.',
    },
    confidence: {
      type: Type.NUMBER,
      description: 'Extraction clarity confidence score between 0.0 and 1.0.',
    },
    assumptions: {
      type: Type.STRING,
      nullable: true,
      description: 'Brief explanation of unambiguous interpretations (e.g. $ -> USD, relative due date), or null.',
    },
  },
  required: [
    'clientName',
    'clientEmail',
    'description',
    'amount',
    'currency',
    'dueDate',
    'confidence',
    'assumptions',
  ],
};

export function resolveConfiguredGeminiModel(overrideModel?: string): string {
  const candidate = (overrideModel ?? process.env['GEMINI_MODEL'] ?? '').trim();
  return candidate.length > 0 ? candidate : DEFAULT_GEMINI_MODEL;
}

export function isGeminiApiKeyConfigured(overrideApiKey?: string): boolean {
  const apiKey = (overrideApiKey ?? process.env['GEMINI_API_KEY'] ?? '').trim();
  return apiKey.length > 0 && apiKey !== GEMINI_PLACEHOLDER_KEY;
}

export async function extractInvoiceFromPrompt(
  prompt: string,
  referenceDate: string,
  deps: ExtractionDependencies = {}
): Promise<InvoiceExtractionOutcome> {
  const demoMode = deps.demoMode ?? process.env['DEMO_MODE'] === 'true';
  const configuredModel = resolveConfiguredGeminiModel(deps.model);

  if (demoMode) {
    return {
      parsed: extractInvoiceInDemoMode(prompt, referenceDate),
      sourceMode: 'demo',
      modelUsed: 'demo-mock-extractor',
      tokenUsage: {
        promptTokens: 0,
        completionTokens: 0,
      },
    };
  }

  const client = deps.client ?? createGeminiClient(deps.apiKey);

  try {
    const response = await client.models.generateContent({
      model: configuredModel,
      contents: prompt,
      config: {
        systemInstruction: buildSystemPrompt(referenceDate),
        responseMimeType: 'application/json',
        responseSchema: GEMINI_INVOICE_RESPONSE_SCHEMA,
      },
    });

    ensureResponseNotBlocked(response);

    const rawText = response.text?.trim();
    if (!rawText) {
      throw new AiUpstreamError('Gemini returned an empty structured response.');
    }

    let rawJson: unknown;
    try {
      rawJson = JSON.parse(rawText);
    } catch {
      throw new AiUpstreamError('Gemini returned malformed non-JSON structured output.');
    }

    const zodResult = AiInvoiceExtractionSchema.safeParse(rawJson);
    if (!zodResult.success) {
      throw new AiUpstreamError('Gemini structured output failed schema validation.');
    }

    return {
      parsed: zodResult.data,
      sourceMode: 'live',
      modelUsed: response.modelVersion?.trim() || configuredModel,
      tokenUsage: {
        promptTokens: response.usageMetadata?.promptTokenCount ?? null,
        completionTokens: response.usageMetadata?.candidatesTokenCount ?? null,
      },
    };
  } catch (err) {
    throw mapProviderError(err);
  }
}

function createGeminiClient(overrideApiKey?: string): GeminiGenerateClient {
  if (!isGeminiApiKeyConfigured(overrideApiKey)) {
    throw new AiConfigurationError('Gemini API key is not configured for live extraction.');
  }
  const apiKey = (overrideApiKey ?? process.env['GEMINI_API_KEY'] ?? '').trim();
  return new GoogleGenAI({ apiKey });
}

function ensureResponseNotBlocked(response: GeminiGenerateResponse): void {
  const blockReason = response.promptFeedback?.blockReason;
  if (blockReason && blockReason !== 'BLOCKED_REASON_UNSPECIFIED') {
    throw new AiUpstreamError(`Gemini blocked extraction prompt (${blockReason}).`);
  }

  const finishReason = response.candidates?.[0]?.finishReason;
  if (finishReason && finishReason !== FinishReason.STOP) {
    throw new AiUpstreamError(`Gemini extraction did not finish cleanly (${finishReason}).`);
  }
}

function mapProviderError(err: unknown): AiConfigurationError | AiUpstreamError {
  if (err instanceof AiConfigurationError || err instanceof AiUpstreamError) {
    return err;
  }

  const rawStatus = extractErrorStatus(err);
  const sanitizedMessage = sanitizeErrorMessage(err);

  if (
    rawStatus === 429 ||
    /resource_exhausted|rate[ _-]?limit|quota/i.test(sanitizedMessage)
  ) {
    return new AiRateLimitError(
      'Gemini API quota or rate limit exceeded. Please wait and retry shortly.'
    );
  }

  if (rawStatus === 401 || rawStatus === 403 || /api[_ ]?key/i.test(sanitizedMessage)) {
    return new AiUpstreamError('Gemini API authentication or permission failed.');
  }

  return new AiUpstreamError(sanitizedMessage || 'Gemini extraction request failed.');
}

function extractErrorStatus(err: unknown): number | undefined {
  if (err instanceof ApiError) {
    return err.status;
  }
  if (err && typeof err === 'object' && 'status' in err) {
    const candidate = (err as { status?: unknown }).status;
    if (typeof candidate === 'number' && Number.isFinite(candidate)) {
      return candidate;
    }
  }
  return undefined;
}

function sanitizeErrorMessage(err: unknown): string {
  const message = err instanceof Error ? err.message : '';
  return message
    .replace(/([?&]key=)[^&\s]+/gi, '$1[REDACTED]')
    .replace(/\bAIza[0-9A-Za-z_-]{20,}\b/g, '[REDACTED]')
    .trim();
}

function buildSystemPrompt(referenceDate: string): string {
  return [
    'You are an invoice data extraction assistant for PayPilot AI.',
    `Today reference date is ${referenceDate} (YYYY-MM-DD).`,
    'Extract invoice draft fields from the user request into the required JSON structure.',
    'Rules:',
    '1. Extract ONLY details stated or unambiguously implied in the prompt.',
    '2. NEVER invent or guess missing client names, email addresses, amounts, descriptions, or dates. Return null for any missing field.',
    '3. If a "$" symbol appears without another currency specified, interpret currency as "USD" and note that in assumptions.',
    '4. If a relative due date is given (e.g. "due in seven days", "net 14"), compute the exact YYYY-MM-DD date from the reference date and explain the calculation in assumptions.',
    '5. Set confidence between 0.0 and 1.0 reflecting how clear and complete the prompt was.',
  ].join('\n');
}
