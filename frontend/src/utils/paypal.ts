import { ApiClientError, type PaymentResponse } from '@/types';
import { formatInvoiceMoney } from './money';

const ORDER_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

export interface PaymentFeedbackMessage {
  tone: 'success' | 'warning' | 'error' | 'info';
  title: string;
  message: string;
  retryOrderId?: string;
}

/**
 * Formats an authoritative feedback banner from a backend PaymentResponse returned
 * by POST /api/payments/orders/{orderId}/capture.
 */
export function describeCaptureOutcome(payment: PaymentResponse): PaymentFeedbackMessage {
  if (payment.pending || payment.status === 'PENDING') {
    const reasonText = payment.pendingReason ? ` (${payment.pendingReason})` : '';
    return {
      tone: 'warning',
      title: 'PayPal Sandbox capture is PENDING',
      message: `Order ${payment.paypalOrderId} returned PENDING${reasonText}. The invoice remains APPROVED and is NOT marked PAID until PayPal confirms a COMPLETED capture.`,
      retryOrderId: payment.paypalOrderId,
    };
  }

  if (payment.completed && payment.status === 'CAPTURED' && payment.invoiceStatus === 'PAID') {
    const captureText = payment.paypalCaptureId ? ` (Capture ID: ${payment.paypalCaptureId})` : '';
    const replayPrefix = payment.reused ? 'Payment was already verified: ' : '';
    return {
      tone: 'success',
      title: 'PayPal Sandbox payment verified',
      message: `${replayPrefix}${formatInvoiceMoney(payment.amount, payment.currency)} captured for ${payment.invoiceNumber}${captureText}. Invoice status is now PAID.`,
    };
  }

  return {
    tone: 'info',
    title: `Payment status updated: ${payment.status}`,
    message: `Order ${payment.paypalOrderId} is currently in status ${payment.status}.`,
  };
}

/**
 * Translates payment API failures cleanly without claiming an ambiguous timeout failed,
 * and surfacing safe recovery guidance for 409 / 422 / 502 / 503 / network conditions.
 */
export function describePaymentFailure(
  error: unknown,
  orderId?: string
): PaymentFeedbackMessage {
  if (!(error instanceof ApiClientError)) {
    return {
      tone: 'error',
      title: 'Unexpected payment verification issue',
      message: 'Unable to complete the payment request. Refresh invoice details before retrying.',
      retryOrderId: orderId,
    };
  }

  if (error.isNetworkError || error.status === 502 || error.status === 503) {
    return {
      tone: 'warning',
      title: 'PayPal Sandbox verification status uncertain',
      message: `${error.message} Do not create a duplicate order — use "Check Payment Status" to safely query PayPal Sandbox.`,
      retryOrderId: orderId,
    };
  }

  if (error.status === 409) {
    return {
      tone: 'warning',
      title: 'Invoice or payment state changed (409 Conflict)',
      message: `${error.message} Latest invoice and payment details have been refreshed.`,
    };
  }

  if (error.status === 422) {
    return {
      tone: 'error',
      title: 'Payment declined or verification rejected',
      message: error.message,
    };
  }

  if (error.status === 403 || error.status === 404) {
    return {
      tone: 'error',
      title: error.status === 403 ? 'Access denied' : 'Payment or invoice not found',
      message: error.message,
    };
  }

  return {
    tone: 'error',
    title: `Payment error (${error.error})`,
    message: error.message,
  };
}

/**
 * Validates that an external checkout URL strictly targets HTTPS PayPal Sandbox
 * hosts (`sandbox.paypal.com` or `*.sandbox.paypal.com`), rejecting live PayPal
 * or arbitrary untrusted domains before browser navigation.
 */
export function parsePayPalSandboxApprovalUrl(rawUrl: string | null | undefined): string | null {
  if (!rawUrl || !rawUrl.trim()) {
    return null;
  }

  let parsed: URL;
  try {
    parsed = new URL(rawUrl.trim());
  } catch {
    return null;
  }

  const host = parsed.hostname.toLowerCase();
  const isSandboxHost = host === 'sandbox.paypal.com' || host.endsWith('.sandbox.paypal.com');

  if (
    parsed.protocol !== 'https:' ||
    !isSandboxHost ||
    parsed.username !== '' ||
    parsed.password !== '' ||
    parsed.port !== ''
  ) {
    return null;
  }

  return parsed.toString();
}

export function isValidPayPalOrderToken(token: string | null | undefined): token is string {
  if (!token) {
    return false;
  }
  return ORDER_ID_PATTERN.test(token.trim());
}

/**
 * Builds frontend return/cancel URLs matching PaymentService's default redirect pattern
 * (`/invoices/{id}?paypal=return|cancel`), compatible with Vite (`localhost:5173`).
 */
export function buildPayPalRedirectUrls(invoiceId: number): {
  returnUrl: string;
  cancelUrl: string;
} {
  const origin = window.location.origin;
  return {
    returnUrl: `${origin}/invoices/${invoiceId}?paypal=return`,
    cancelUrl: `${origin}/invoices/${invoiceId}?paypal=cancel`,
  };
}

export function buildPublicPayPalRedirectUrls(token: string): {
  returnUrl: string;
  cancelUrl: string;
} {
  const origin = window.location.origin;
  const encodedToken = encodeURIComponent(token);
  return {
    returnUrl: `${origin}/pay/${encodedToken}?paypal=return`,
    cancelUrl: `${origin}/pay/${encodedToken}?paypal=cancel`,
  };
}
