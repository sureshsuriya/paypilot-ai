import { z } from 'zod';

/**
 * Input payload sent by the Spring Boot backend to /api/ai/extract.
 */
export const ExtractInvoiceInputSchema = z.object({
  prompt: z
    .string({ required_error: 'Prompt is required' })
    .trim()
    .min(3, 'Prompt must be at least 3 characters')
    .max(2000, 'Prompt must not exceed 2000 characters'),
  referenceDate: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'referenceDate must be YYYY-MM-DD')
    .optional(),
});

export type ExtractInvoiceInput = z.infer<typeof ExtractInvoiceInputSchema>;

/**
 * Strict structured output schema passed to OpenAI GPT-4o via zodResponseFormat.
 * All fields are required and nullable so GPT-4o explicitly returns null for
 * missing information instead of guessing or fabricating values.
 */
export const OpenAiInvoiceSchema = z.object({
  clientName: z.string().nullable(),
  clientEmail: z.string().nullable(),
  description: z.string().nullable(),
  amount: z.number().nullable(),
  currency: z.string().nullable(),
  dueDate: z.string().nullable(),
  confidence: z.number().min(0).max(1),
  assumptions: z.string().nullable(),
});

export type OpenAiInvoiceResult = z.infer<typeof OpenAiInvoiceSchema>;

export interface NormalizedDraftFields {
  clientName: string | null;
  clientEmail: string | null;
  description: string | null;
  amount: number | null;
  currency: string | null;
  dueDate: string | null;
}

export interface EvaluatedExtractionDraft {
  draft: NormalizedDraftFields;
  missingFields: string[];
  validationErrors: Record<string, string>;
  readyToSave: boolean;
}

const EMAIL_REGEX = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const CURRENCY_REGEX = /^[A-Z]{3}$/;
const ISO_DATE_REGEX = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Checks whether a YYYY-MM-DD string represents an actual calendar date.
 */
export function isValidIsoDate(dateStr: string): boolean {
  if (!ISO_DATE_REGEX.test(dateStr)) {
    return false;
  }
  const [yStr, mStr, dStr] = dateStr.split('-');
  const year = Number(yStr);
  const month = Number(mStr);
  const day = Number(dStr);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return (
    parsed.getUTCFullYear() === year &&
    parsed.getUTCMonth() === month - 1 &&
    parsed.getUTCDate() === day
  );
}

/**
 * Validates and normalizes AI-extracted invoice fields against business rules,
 * identifying missing details vs invalid values without inventing data.
 */
export function validateAndEvaluateDraft(
  raw: OpenAiInvoiceResult,
  referenceDate: string
): EvaluatedExtractionDraft {
  const missingFields: string[] = [];
  const validationErrors: Record<string, string> = {};

  const clientName = normalizeOptionalText(raw.clientName);
  if (clientName === null) {
    missingFields.push('clientName');
  } else if (clientName.length > 255) {
    validationErrors['clientName'] = 'Client name must not exceed 255 characters';
  }

  const rawEmail = normalizeOptionalText(raw.clientEmail);
  const clientEmail = rawEmail !== null ? rawEmail.toLowerCase() : null;
  if (clientEmail === null) {
    missingFields.push('clientEmail');
  } else if (clientEmail.length > 255 || !EMAIL_REGEX.test(clientEmail)) {
    validationErrors['clientEmail'] = 'Client email must be a valid email address';
  }

  const description = normalizeOptionalText(raw.description);
  if (description === null) {
    missingFields.push('description');
  } else if (description.length > 5000) {
    validationErrors['description'] = 'Description must not exceed 5000 characters';
  }

  let amount: number | null = null;
  if (raw.amount === null || raw.amount === undefined) {
    missingFields.push('amount');
  } else if (!Number.isFinite(raw.amount) || raw.amount < 0.01) {
    validationErrors['amount'] = 'Amount must be at least 0.01';
    amount = raw.amount;
  } else if (hasMoreThanTwoDecimals(raw.amount)) {
    validationErrors['amount'] = 'Amount must have at most 2 decimal places';
    amount = raw.amount;
  } else {
    amount = Math.round(raw.amount * 100) / 100;
  }

  const rawCurrency = normalizeOptionalText(raw.currency);
  const currency = rawCurrency !== null ? rawCurrency.toUpperCase() : null;
  if (currency === null) {
    missingFields.push('currency');
  } else if (!CURRENCY_REGEX.test(currency)) {
    validationErrors['currency'] = 'Currency must be a 3-letter ISO code (e.g. USD)';
  }

  const dueDate = normalizeOptionalText(raw.dueDate);
  if (dueDate === null) {
    missingFields.push('dueDate');
  } else if (!isValidIsoDate(dueDate)) {
    validationErrors['dueDate'] = 'Due date must be a valid YYYY-MM-DD date';
  } else if (dueDate < referenceDate) {
    validationErrors['dueDate'] = 'Due date must be today or in the future';
  }

  const readyToSave =
    missingFields.length === 0 && Object.keys(validationErrors).length === 0;

  return {
    draft: {
      clientName,
      clientEmail,
      description,
      amount,
      currency,
      dueDate,
    },
    missingFields,
    validationErrors,
    readyToSave,
  };
}

function normalizeOptionalText(value: string | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
}

function hasMoreThanTwoDecimals(value: number): boolean {
  // Compare scaled integer within tiny IEEE-754 tolerance
  const scaled = value * 100;
  return Math.abs(scaled - Math.round(scaled)) > 1e-6;
}

