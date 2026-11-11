import OpenAI from 'openai';
import { zodResponseFormat } from 'openai/helpers/zod';
import {
  OpenAiInvoiceSchema,
  type OpenAiInvoiceResult,
} from '../validation/extractionSchema.js';
import { extractInvoiceInDemoMode } from '../mock/mockExtractor.js';

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

export interface TokenUsage {
  promptTokens: number | null;
  completionTokens: number | null;
}

export interface InvoiceExtractionOutcome {
  parsed: OpenAiInvoiceResult;
  sourceMode: 'live' | 'demo';
  modelUsed: string;
  tokenUsage: TokenUsage;
}

/**
 * Minimal subset of the OpenAI client used by the extractor so tests can
 * inject a mock without making any live API requests or incurring costs.
 */
export interface OpenAiParseClient {
  beta: {
    chat: {
      completions: {
        parse(params: unknown): Promise<{
          model?: string;
          usage?: {
            prompt_tokens?: number;
            completion_tokens?: number;
          } | null;
          choices: Array<{
            message?: {
              parsed?: OpenAiInvoiceResult | null;
              refusal?: string | null;
            };
          }>;
        }>;
      };
    };
  };
}

export interface ExtractionDependencies {
  client?: OpenAiParseClient;
  demoMode?: boolean;
  model?: string;
  apiKey?: string;
}

export async function extractInvoiceFromPrompt(
  prompt: string,
  referenceDate: string,
  deps: ExtractionDependencies = {}
): Promise<InvoiceExtractionOutcome> {
  const demoMode = deps.demoMode ?? process.env['DEMO_MODE'] === 'true';
  const configuredModel = deps.model ?? process.env['OPENAI_MODEL'] ?? 'gpt-4o';

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

  const client = deps.client ?? createOpenAiClient(deps.apiKey);

  try {
    const completion = await client.beta.chat.completions.parse({
      model: configuredModel,
      temperature: 0,
      messages: [
        {
          role: 'system',
          content: buildSystemPrompt(referenceDate),
        },
        {
          role: 'user',
          content: prompt,
        },
      ],
      response_format: zodResponseFormat(OpenAiInvoiceSchema, 'invoice_extraction'),
    });

    const firstMessage = completion.choices[0]?.message;
    if (firstMessage?.refusal) {
      throw new AiUpstreamError(`Model refused extraction: ${firstMessage.refusal}`);
    }
    if (!firstMessage?.parsed) {
      throw new AiUpstreamError('OpenAI returned an empty structured response.');
    }

    const validated = OpenAiInvoiceSchema.parse(firstMessage.parsed);

    return {
      parsed: validated,
      sourceMode: 'live',
      modelUsed: completion.model ?? configuredModel,
      tokenUsage: {
        promptTokens: completion.usage?.prompt_tokens ?? null,
        completionTokens: completion.usage?.completion_tokens ?? null,
      },
    };
  } catch (err) {
    if (err instanceof AiConfigurationError || err instanceof AiUpstreamError) {
      throw err;
    }
    const safeMsg = err instanceof Error ? err.message : 'OpenAI extraction request failed';
    throw new AiUpstreamError(safeMsg);
  }
}

function createOpenAiClient(overrideApiKey?: string): OpenAiParseClient {
  const apiKey = overrideApiKey ?? process.env['OPENAI_API_KEY'];
  if (!apiKey || apiKey === 'your_openai_api_key_here') {
    throw new AiConfigurationError('OpenAI API key is not configured for live extraction.');
  }
  return new OpenAI({ apiKey }) as unknown as OpenAiParseClient;
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

