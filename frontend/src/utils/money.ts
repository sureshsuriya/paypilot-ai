import type { InvoiceResponse, InvoiceStatus, PaymentResponse } from '@/types';

const EXACT_TWO_DECIMAL_PATTERN = /^(\d{1,13})(?:\.(\d{1,2}))?$/;

const OPEN_OUTSTANDING_STATUSES: ReadonlySet<InvoiceStatus> = new Set([
  'DRAFT',
  'APPROVED',
  'SENT',
  'OVERDUE',
]);

export interface CurrencySubtotal {
  currency: string;
  cents: bigint;
  formatted: string;
}

/**
 * Parses a positive/zero decimal value with up to 13 integer digits and 2 decimal places
 * into exact minor units (BigInt cents) without binary floating-point rounding drift.
 */
export function parseExactCents(raw: number | string | null | undefined): bigint | null {
  if (raw === null || raw === undefined) {
    return null;
  }
  const normalized =
    typeof raw === 'number'
      ? Number.isFinite(raw)
        ? raw.toString()
        : ''
      : raw.trim();

  if (!normalized) {
    return null;
  }

  const match = EXACT_TWO_DECIMAL_PATTERN.exec(normalized);
  if (!match) {
    return null;
  }

  const wholePart = BigInt(match[1]);
  const fractionRaw = match[2] ?? '';
  const fractionCents = BigInt(fractionRaw.padEnd(2, '0'));
  return wholePart * 100n + fractionCents;
}

/**
 * Formats an exact minor-unit amount cleanly without float conversion loss.
 */
export function formatMinorUnits(cents: bigint, currency: string): string {
  const code = (currency || 'USD').trim().toUpperCase();
  const isNegative = cents < 0n;
  const abs = isNegative ? -cents : cents;
  const whole = abs / 100n;
  const fraction = (abs % 100n).toString().padStart(2, '0');
  const groupedWhole = whole.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const sign = isNegative ? '-' : '';

  try {
    const parts = new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: code,
    }).formatToParts(0);
    const symbolPart = parts.find((part) => part.type === 'currency')?.value;
    if (symbolPart) {
      return `${sign}${symbolPart}${groupedWhole}.${fraction} ${code}`;
    }
  } catch {
    // Fall back to ISO currency prefix when code is unrecognized by Intl.
  }

  return `${sign}${code} ${groupedWhole}.${fraction}`;
}

export function formatInvoiceMoney(
  amount: number | string | null | undefined,
  currency: string
): string {
  const code = (currency || 'USD').trim().toUpperCase();
  const cents = parseExactCents(amount);
  if (cents !== null) {
    return formatMinorUnits(cents, code);
  }
  const num = typeof amount === 'number' ? amount : Number(amount);
  if (Number.isFinite(num)) {
    return `${code} ${num.toFixed(2)}`;
  }
  return `${code} 0.00`;
}

/**
 * Computes exact outstanding totals grouped by ISO currency across open invoice states
 * (DRAFT, APPROVED, SENT, OVERDUE). Never combines distinct currencies or rounds floats.
 */
export function summarizeOutstandingByCurrency(
  invoices: InvoiceResponse[]
): CurrencySubtotal[] {
  const totals = new Map<string, bigint>();

  for (const invoice of invoices) {
    if (!OPEN_OUTSTANDING_STATUSES.has(invoice.status)) {
      continue;
    }
    const cents = parseExactCents(invoice.amount);
    if (cents === null) {
      continue;
    }
    const currency = (invoice.currency || 'USD').trim().toUpperCase();
    totals.set(currency, (totals.get(currency) ?? 0n) + cents);
  }

  return Array.from(totals.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([currency, cents]) => ({
      currency,
      cents,
      formatted: formatMinorUnits(cents, currency),
    }));
}

/**
 * Sums verified CAPTURED payments grouped by ISO currency without floating-point drift.
 */
export function summarizeCapturedRevenueByCurrency(
  payments: PaymentResponse[]
): CurrencySubtotal[] {
  const totals = new Map<string, bigint>();

  for (const payment of payments) {
    if (payment.status !== 'CAPTURED') {
      continue;
    }
    const cents = parseExactCents(payment.amount);
    if (cents === null) {
      continue;
    }
    const currency = (payment.currency || 'USD').trim().toUpperCase();
    totals.set(currency, (totals.get(currency) ?? 0n) + cents);
  }

  return Array.from(totals.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([currency, cents]) => ({
      currency,
      cents,
      formatted: formatMinorUnits(cents, currency),
    }));
}

export function formatDisplayDate(isoDateOrTimestamp: string | null | undefined): string {
  if (!isoDateOrTimestamp) {
    return '—';
  }
  const dateOnlyMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDateOrTimestamp.trim());
  if (dateOnlyMatch) {
    const year = Number(dateOnlyMatch[1]);
    const month = Number(dateOnlyMatch[2]);
    const day = Number(dateOnlyMatch[3]);
    const utcDate = new Date(Date.UTC(year, month - 1, day));
    return new Intl.DateTimeFormat('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      timeZone: 'UTC',
    }).format(utcDate);
  }

  const parsed = new Date(isoDateOrTimestamp);
  if (Number.isNaN(parsed.getTime())) {
    return isoDateOrTimestamp;
  }
  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(parsed);
}

