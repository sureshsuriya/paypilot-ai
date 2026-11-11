import type { OpenAiInvoiceResult } from '../validation/extractionSchema.js';

const WORD_NUMBERS: Record<string, number> = {
  one: 1,
  two: 2,
  three: 3,
  four: 4,
  five: 5,
  six: 6,
  seven: 7,
  eight: 8,
  nine: 9,
  ten: 10,
  fourteen: 14,
  fifteen: 15,
  thirty: 30,
};

/**
 * Deterministic extractor used ONLY when DEMO_MODE=true.
 * Never invents missing fields such as client emails, amounts, or client names.
 */
export function extractInvoiceInDemoMode(
  prompt: string,
  referenceDate: string
): OpenAiInvoiceResult {
  const emailMatch = prompt.match(/\b([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b/);
  const clientEmail = emailMatch ? emailMatch[1] : null;

  let currency: string | null = null;
  let amount: number | null = null;

  const dollarMatch = prompt.match(/\$\s*(\d+(?:\.\d{1,2})?)/);
  const isoMatch = prompt.match(/\b(USD|EUR|GBP|CAD|AUD)\s*(\d+(?:\.\d{1,2})?)\b/i);
  const trailingCurrencyMatch = prompt.match(/\b(\d+(?:\.\d{1,2})?)\s*(USD|EUR|GBP|CAD|AUD|dollars?)\b/i);

  if (dollarMatch) {
    amount = Number(dollarMatch[1]);
    currency = 'USD';
  } else if (isoMatch) {
    currency = isoMatch[1].toUpperCase();
    amount = Number(isoMatch[2]);
  } else if (trailingCurrencyMatch) {
    amount = Number(trailingCurrencyMatch[1]);
    const rawCur = trailingCurrencyMatch[2].toUpperCase();
    currency = rawCur.startsWith('DOLLAR') ? 'USD' : rawCur;
  }

  let clientName: string | null = null;
  let description: string | null = null;

  const clientAndServiceMatch = prompt.match(
    /\bfor\s+([A-Za-z][A-Za-z\s.'-]{0,60}?)\s+for\s+([^.]+?)(?:\.|$)/i
  );
  if (clientAndServiceMatch) {
    clientName = clientAndServiceMatch[1].trim();
    description = clientAndServiceMatch[2].trim();
  } else {
    const singleForMatch = prompt.match(/\bfor\s+([A-Za-z][A-Za-z\s.'-]{0,60}?)(?:\.|,|$)/i);
    if (singleForMatch) {
      clientName = singleForMatch[1].trim();
    }
  }

  let dueDate: string | null = null;
  const isoDateMatch = prompt.match(/\b(\d{4}-\d{2}-\d{2})\b/);
  const relativeDaysMatch = prompt.match(
    /\bdue\s+(?:in|within)\s+(\d+|one|two|three|four|five|six|seven|eight|nine|ten|fourteen|fifteen|thirty)\s+days?\b/i
  );

  if (isoDateMatch) {
    dueDate = isoDateMatch[1];
  } else if (relativeDaysMatch) {
    const token = relativeDaysMatch[1].toLowerCase();
    const days = /^\d+$/.test(token) ? Number(token) : (WORD_NUMBERS[token] ?? null);
    if (days !== null) {
      dueDate = addDaysIso(referenceDate, days);
    }
  }

  return {
    clientName,
    clientEmail,
    description,
    amount,
    currency,
    dueDate,
    confidence: 0.9,
    assumptions: '[DEMO MODE] Deterministic parser used; missing fields were left null.',
  };
}

function addDaysIso(baseDate: string, days: number): string {
  const [year, month, day] = baseDate.split('-').map(Number);
  const utc = new Date(Date.UTC(year, month - 1, day + days));
  return utc.toISOString().slice(0, 10);
}

