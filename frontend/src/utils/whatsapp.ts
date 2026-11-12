import type { InvoiceResponse } from '@/types';
import { formatDisplayDate, formatInvoiceMoney } from './money';
import { normalizeAndValidateOptionalPhone } from './validation';

export function formatWhatsAppInvoiceMessage(
  invoice: InvoiceResponse,
  paymentUrl: string
): string {
  const customerName = invoice.clientName?.trim() || 'Customer';
  const amountFormatted = formatInvoiceMoney(invoice.amount, invoice.currency);
  const description = invoice.description?.trim() || 'Invoice payment';
  const dueDate = invoice.dueDate ? formatDisplayDate(invoice.dueDate) : 'Upon receipt';

  return [
    `Hello ${customerName},`,
    '',
    `Your invoice ${invoice.invoiceNumber} is ready.`,
    '',
    `Amount: ${amountFormatted}`,
    `Description: ${description}`,
    `Due date: ${dueDate}`,
    '',
    'Please review and pay securely using this link:',
    '',
    paymentUrl,
    '',
    'Thank you!',
    '',
    'PayPilot AI',
  ].join('\n');
}

export function buildWhatsAppShareUrl(
  rawPhone: string | null | undefined,
  message: string
): { url?: string; normalizedPhone?: string; error?: string } {
  if (!rawPhone || !rawPhone.trim()) {
    return {
      error:
        'Customer WhatsApp phone number is required. Enter an international E.164 number starting with + (for example, +14155552671).',
    };
  }
  const check = normalizeAndValidateOptionalPhone(rawPhone);
  if (check.error || !check.normalized) {
    return {
      error:
        check.error ??
        'Include country code starting with + and 7 to 15 digits (for example, +14155552671).',
    };
  }
  const digitsOnly = check.normalized.slice(1);
  const encodedText = encodeURIComponent(message);
  return {
    normalizedPhone: check.normalized,
    url: `https://wa.me/${digitsOnly}?text=${encodedText}`,
  };
}

