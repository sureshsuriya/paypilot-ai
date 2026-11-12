import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { publicPayApi } from '@/api';
import { InvoiceStatusBadge } from '@/components/invoices/InvoiceStatusBadge';
import { PaymentFeedbackBanner } from '@/components/payments/PaymentFeedbackBanner';
import { CustomerPortalLayout } from '@/components/public/CustomerPortalLayout';
import { CustomerReceiptCard } from '@/components/public/CustomerReceiptCard';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { RefreshIcon, ShieldCheckIcon } from '@/components/ui/Icons';
import {
  ApiClientError,
  type PublicPaymentInvoiceResponse,
} from '@/types';
import { formatDisplayDate, formatInvoiceMoney } from '@/utils/money';
import {
  buildPublicPayPalRedirectUrls,
  describePaymentFailure,
  isValidPayPalOrderToken,
  parsePayPalSandboxApprovalUrl,
  type PaymentFeedbackMessage,
} from '@/utils/paypal';

const handledPublicPayPalCallbacks = new Set<string>();

function describeUnavailableState(data: PublicPaymentInvoiceResponse): {
  title: string;
  message: string;
  tone: 'warning' | 'error' | 'info';
} {
  if (data.linkState === 'REVOKED') {
    return {
      title: 'Payment link revoked',
      message: data.statusMessage || 'This payment link has been revoked by the seller and can no longer be used.',
      tone: 'error',
    };
  }
  if (data.linkState === 'EXPIRED') {
    return {
      title: 'Payment link expired',
      message: data.statusMessage || 'This payment link has expired. Please ask the seller for a fresh link.',
      tone: 'warning',
    };
  }
  return {
    title: 'Invoice unavailable for payment',
    message: data.statusMessage || 'This invoice is no longer open for payment.',
    tone: 'warning',
  };
}

export function CustomerPaymentPage() {
  const { token } = useParams<{ token: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const linkToken = token?.trim() ?? '';

  const [invoiceData, setInvoiceData] = useState<PublicPaymentInvoiceResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<{ status: number; message: string } | null>(null);
  const [feedback, setFeedback] = useState<PaymentFeedbackMessage | null>(null);
  const [isStartingCheckout, setIsStartingCheckout] = useState(false);
  const [isProcessingCallback, setIsProcessingCallback] = useState(false);
  const [checkingOrderId, setCheckingOrderId] = useState<string | null>(null);
  const isCheckingPending = checkingOrderId !== null;

  const mountedRef = useRef(true);
  const loadSeqRef = useRef(0);
  const searchParamsRef = useRef(searchParams);
  searchParamsRef.current = searchParams;
  const initialLoadTokenRef = useRef<string | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const fetchPublicInvoice = useCallback(
    async (background = false, trackView = false) => {
      if (!linkToken) {
        setIsLoading(false);
        setLoadError({ status: 404, message: 'Invalid or missing payment link.' });
        return;
      }

      const seq = ++loadSeqRef.current;
      if (background) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setLoadError(null);

      try {
        const result = await publicPayApi.getPublicInvoice(linkToken, { trackView });
        if (!mountedRef.current || seq !== loadSeqRef.current) {
          return;
        }
        setInvoiceData(result);
      } catch (error) {
        if (!mountedRef.current || seq !== loadSeqRef.current) {
          return;
        }
        if (error instanceof ApiClientError) {
          setLoadError({ status: error.status, message: error.message });
        } else {
          setLoadError({
            status: 0,
            message: 'Unable to load payment details right now. Please try again.',
          });
        }
      } finally {
        if (mountedRef.current && seq === loadSeqRef.current) {
          setIsLoading(false);
          setIsRefreshing(false);
        }
      }
    },
    [linkToken]
  );

  useEffect(() => {
    // Guard against React 18 StrictMode double-effect invocation firing two view increments.
    if (initialLoadTokenRef.current === linkToken) {
      return;
    }
    initialLoadTokenRef.current = linkToken;

    const modeParam = searchParamsRef.current.get('paypal')?.toLowerCase() ?? null;
    const isPayPalCallback = modeParam === 'return' || modeParam === 'cancel';
    void fetchPublicInvoice(false, !isPayPalCallback);
  }, [linkToken, fetchPublicInvoice]);

  const stripPayPalQueryParams = useCallback(() => {
    const next = new URLSearchParams(searchParams);
    let changed = false;
    for (const key of ['paypal', 'token', 'PayerID', 'ba_token']) {
      if (next.has(key)) {
        next.delete(key);
        changed = true;
      }
    }
    if (changed) {
      setSearchParams(next, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  // Handle PayPal Sandbox return (?paypal=return&token=ORDER_ID) or cancel (?paypal=cancel&token=ORDER_ID)
  useEffect(() => {
    if (!linkToken) {
      return;
    }

    const modeParam = searchParams.get('paypal')?.toLowerCase() ?? null;
    if (modeParam !== 'return' && modeParam !== 'cancel') {
      return;
    }

    const orderToken = searchParams.get('token')?.trim() ?? null;
    const callbackKey = `${linkToken}:${modeParam}:${orderToken ?? 'missing'}`;
    if (handledPublicPayPalCallbacks.has(callbackKey)) {
      return;
    }
    handledPublicPayPalCallbacks.add(callbackKey);

    const runPayPalReturnFlow = async () => {
      if (mountedRef.current) {
        setIsProcessingCallback(true);
      }

      try {
        if (modeParam === 'return') {
          if (!isValidPayPalOrderToken(orderToken)) {
            if (mountedRef.current) {
              setFeedback({
                tone: 'error',
                title: 'PayPal order token missing',
                message:
                  'PayPal returned without an order reference. Refresh the page before trying again.',
              });
            }
            return;
          }

          try {
            const updated = await publicPayApi.captureOrder(linkToken, { orderId: orderToken });
            if (!mountedRef.current) {
              return;
            }
            setInvoiceData(updated);
            if (updated.linkState === 'PAID' && updated.receipt) {
              setFeedback(null);
            } else if (updated.activePayment?.status === 'PENDING') {
              const reasonText = updated.activePayment.pendingReason
                ? ` (${updated.activePayment.pendingReason})`
                : '';
              setFeedback({
                tone: 'warning',
                title: 'PayPal Sandbox capture is PENDING',
                message: `Order ${updated.activePayment.paypalOrderId} returned PENDING${reasonText}. The invoice is not marked PAID yet. Use "Check Payment Status" to verify once PayPal settles.`,
                retryOrderId: updated.activePayment.paypalOrderId,
              });
            }
          } catch (error) {
            if (mountedRef.current) {
              setFeedback(describePaymentFailure(error, orderToken));
              await fetchPublicInvoice(true);
            }
          }
        } else {
          try {
            const cancelledView = await publicPayApi.cancelOrder(linkToken, {
              orderId: isValidPayPalOrderToken(orderToken) ? orderToken : undefined,
              reason: 'Checkout cancelled by customer on PayPal Sandbox',
            });
            if (mountedRef.current) {
              setInvoiceData(cancelledView);
              setFeedback({
                tone: 'info',
                title: 'PayPal checkout cancelled',
                message: 'Your payment was cancelled before completion. You can review the invoice and try again whenever ready.',
              });
            }
          } catch (error) {
            if (mountedRef.current) {
              setFeedback(describePaymentFailure(error, orderToken ?? undefined));
              await fetchPublicInvoice(true);
            }
          }
        }
      } finally {
        if (mountedRef.current) {
          stripPayPalQueryParams();
          setIsProcessingCallback(false);
        }
      }
    };

    void runPayPalReturnFlow();
  }, [linkToken, searchParams, fetchPublicInvoice, stripPayPalQueryParams]);

  const handlePayWithPayPal = async () => {
    if (!linkToken || isStartingCheckout || isProcessingCallback) {
      return;
    }

    setIsStartingCheckout(true);
    setFeedback(null);

    try {
      const { returnUrl, cancelUrl } = buildPublicPayPalRedirectUrls(linkToken);
      const order = await publicPayApi.createOrder(linkToken, { returnUrl, cancelUrl });
      const approvalUrl = parsePayPalSandboxApprovalUrl(order.approvalUrl);

      if (!approvalUrl) {
        setFeedback({
          tone: 'error',
          title: 'Invalid PayPal Sandbox redirect URL',
          message: 'PayPilot rejected an untrusted or missing PayPal checkout URL. Please contact the seller.',
        });
        return;
      }

      window.location.assign(approvalUrl);
    } catch (error) {
      if (mountedRef.current) {
        setFeedback(describePaymentFailure(error));
        await fetchPublicInvoice(true);
      }
    } finally {
      if (mountedRef.current) {
        setIsStartingCheckout(false);
      }
    }
  };

  const handleRecheckPendingStatus = async (orderId: string) => {
    if (!linkToken || isCheckingPending) {
      return;
    }
    setCheckingOrderId(orderId);
    setFeedback(null);

    try {
      const updated = await publicPayApi.captureOrder(linkToken, { orderId });
      if (!mountedRef.current) {
        return;
      }
      setInvoiceData(updated);
      if (updated.activePayment?.status === 'PENDING') {
        const reasonText = updated.activePayment.pendingReason
          ? ` (${updated.activePayment.pendingReason})`
          : '';
        setFeedback({
          tone: 'warning',
          title: 'Payment still PENDING in PayPal Sandbox',
          message: `Order ${orderId} is still awaiting settlement${reasonText}. Please try again shortly.`,
          retryOrderId: orderId,
        });
      }
    } catch (error) {
      if (mountedRef.current) {
        setFeedback(describePaymentFailure(error, orderId));
        await fetchPublicInvoice(true);
      }
    } finally {
      if (mountedRef.current) {
        setCheckingOrderId(null);
      }
    }
  };

  return (
    <CustomerPortalLayout>
      {isProcessingCallback ? (
        <div
          role="status"
          className="mb-6 rounded-xl border border-primary-100 bg-primary-50/80 px-5 py-4 text-sm text-primary-900 shadow-sm"
        >
          <p className="font-semibold">Verifying PayPal Sandbox checkout with PayPilot AI…</p>
          <p className="mt-1 text-xs text-primary-800/90">
            Please wait while our backend confirms your payment details. Do not close this window.
          </p>
        </div>
      ) : null}

      {feedback ? (
        <div className="mb-6">
          <PaymentFeedbackBanner
            feedback={feedback}
            busyOrderId={checkingOrderId}
            onCheckStatus={(orderId) => void handleRecheckPendingStatus(orderId)}
          />
        </div>
      ) : null}

      {isLoading ? (
        <div
          aria-label="Loading invoice details"
          className="space-y-4 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm"
        >
          <div className="h-5 w-40 animate-pulse rounded bg-slate-200" />
          <div className="h-8 w-56 animate-pulse rounded bg-slate-200" />
          <div className="h-24 w-full animate-pulse rounded-xl bg-slate-100" />
          <div className="h-11 w-full animate-pulse rounded-xl bg-slate-200" />
        </div>
      ) : loadError ? (
        <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
          <AlertBanner
            tone="error"
            title={loadError.status === 404 ? 'Payment link not found' : 'Unable to load payment page'}
            message={
              loadError.status === 404
                ? 'This payment link is invalid or does not exist. Double-check the URL or ask the seller for a fresh link.'
                : loadError.message
            }
          />
          {loadError.status !== 404 ? (
            <div className="mt-5">
              <button
                type="button"
                onClick={() =>
                  void fetchPublicInvoice(false, loadError.status === 0 || loadError.status >= 500)
                }
                className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
              >
                <RefreshIcon className="h-4 w-4" />
                <span>Retry</span>
              </button>
            </div>
          ) : null}
        </section>
      ) : invoiceData?.linkState === 'PAID' && invoiceData.receipt ? (
        <CustomerReceiptCard receipt={invoiceData.receipt} />
      ) : invoiceData &&
        (invoiceData.linkState === 'REVOKED' ||
          invoiceData.linkState === 'EXPIRED' ||
          invoiceData.linkState === 'UNAVAILABLE') ? (
        <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
          {(() => {
            const info = describeUnavailableState(invoiceData);
            return (
              <AlertBanner
                tone={info.tone}
                title={info.title}
                message={info.message}
              />
            );
          })()}
          {invoiceData.expiresAt ? (
            <p className="mt-4 text-xs text-slate-500">
              Link expiration timestamp: {formatDisplayDate(invoiceData.expiresAt)}
            </p>
          ) : null}
        </section>
      ) : invoiceData ? (
        <section
          aria-label="Invoice checkout summary"
          className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm"
        >
          <div className="border-b border-slate-200 bg-slate-50/70 px-6 py-5">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wide text-primary-700">
                  Invoice From {invoiceData.sellerDisplayName ?? 'Merchant'}
                </p>
                <h1 className="mt-1 font-mono text-xl font-bold tracking-tight text-slate-900">
                  {invoiceData.invoiceNumber}
                </h1>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                {invoiceData.invoiceStatus ? (
                  <InvoiceStatusBadge status={invoiceData.invoiceStatus} />
                ) : null}
                <button
                  type="button"
                  onClick={() => void fetchPublicInvoice(true, false)}
                  disabled={isRefreshing}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition hover:bg-slate-50 disabled:opacity-60"
                >
                  <RefreshIcon className={`h-3.5 w-3.5 ${isRefreshing ? 'animate-spin' : ''}`} />
                  <span>{isRefreshing ? 'Refreshing…' : 'Refresh'}</span>
                </button>
              </div>
            </div>
          </div>

          <div className="p-6">
            <div className="flex flex-wrap items-center justify-between gap-4 rounded-xl border border-primary-100 bg-primary-50/50 px-5 py-4">
              <div>
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Total Amount Due
                </p>
                <p className="mt-1 text-3xl font-bold tracking-tight text-slate-900">
                  {formatInvoiceMoney(invoiceData.amount, invoiceData.currency ?? 'USD')}
                </p>
              </div>
              <div className="text-left sm:text-right">
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Due Date
                </p>
                <p className="mt-1 text-sm font-semibold text-slate-900">
                  {formatDisplayDate(invoiceData.dueDate)}
                </p>
              </div>
            </div>

            <dl className="mt-6 grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Billed To
                </dt>
                <dd className="mt-1 font-medium text-slate-900">
                  {invoiceData.customerName}
                </dd>
              </div>

              <div>
                <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Link Valid Until
                </dt>
                <dd className="mt-1 font-medium text-slate-900">
                  {formatDisplayDate(invoiceData.expiresAt)}
                </dd>
              </div>

              <div className="sm:col-span-2">
                <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Service Description
                </dt>
                <dd className="mt-1 whitespace-pre-line rounded-lg border border-slate-200/80 bg-slate-50/50 px-3.5 py-2.5 text-slate-800">
                  {invoiceData.description}
                </dd>
              </div>
            </dl>

            {invoiceData.activePayment?.status === 'PENDING' ? (
              <div className="mt-6 space-y-3 rounded-xl border border-amber-200 bg-amber-50/80 p-4 text-sm text-amber-950">
                <div>
                  <p className="font-semibold">Payment awaiting PayPal verification</p>
                  <p className="mt-1 text-xs leading-relaxed text-amber-900">
                    Order <span className="font-mono font-semibold">{invoiceData.activePayment.paypalOrderId}</span> is currently <span className="font-semibold">PENDING</span>
                    {invoiceData.activePayment.pendingReason ? ` (${invoiceData.activePayment.pendingReason})` : ''}.
                    The invoice will only be marked PAID once PayPal confirms a COMPLETED capture.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => void handleRecheckPendingStatus(invoiceData.activePayment!.paypalOrderId)}
                  disabled={isCheckingPending}
                  className="inline-flex items-center gap-2 rounded-lg bg-amber-900 px-4 py-2 text-xs font-semibold text-white transition hover:bg-amber-800 disabled:opacity-60"
                >
                  <RefreshIcon className={`h-3.5 w-3.5 ${isCheckingPending ? 'animate-spin' : ''}`} />
                  <span>{isCheckingPending ? 'Checking PayPal…' : 'Check Payment Status'}</span>
                </button>
              </div>
            ) : (
              <div className="mt-6 border-t border-slate-200 pt-6">
                <button
                  type="button"
                  onClick={() => void handlePayWithPayPal()}
                  disabled={!invoiceData.payable || isStartingCheckout || isProcessingCallback}
                  className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary-600 px-5 py-3.5 text-sm font-semibold text-white shadow-sm transition hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  <span>
                    {isStartingCheckout
                      ? 'Connecting to PayPal Sandbox…'
                      : 'Pay with PayPal (Sandbox)'}
                  </span>
                </button>
                <div className="mt-3 flex items-center justify-center gap-1.5 text-xs text-slate-500">
                  <ShieldCheckIcon className="h-4 w-4 text-emerald-600" />
                  <span>
                    You will review and approve in PayPal Sandbox, then return here for verified confirmation.
                  </span>
                </div>
              </div>
            )}
          </div>
        </section>
      ) : null}
    </CustomerPortalLayout>
  );
}

