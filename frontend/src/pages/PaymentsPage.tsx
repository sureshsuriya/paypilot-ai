import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { paymentsApi } from '@/api';
import { PaymentFeedbackBanner } from '@/components/payments/PaymentFeedbackBanner';
import { PaymentHistoryTable } from '@/components/payments/PaymentHistoryTable';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { RefreshIcon } from '@/components/ui/Icons';
import {
  ApiClientError,
  type PaymentResponse,
  type PaymentStatus,
} from '@/types';
import {
  describeCaptureOutcome,
  describePaymentFailure,
  parsePayPalSandboxApprovalUrl,
  type PaymentFeedbackMessage,
} from '@/utils/paypal';

type PaymentStatusFilter = 'ALL' | PaymentStatus;

const PAYMENT_STATUS_FILTERS: PaymentStatusFilter[] = [
  'ALL',
  'CREATED',
  'APPROVED',
  'PENDING',
  'CAPTURED',
  'FAILED',
  'CANCELLED',
];

export function PaymentsPage() {
  const [payments, setPayments] = useState<PaymentResponse[]>([]);
  const [statusFilter, setStatusFilter] = useState<PaymentStatusFilter>('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<PaymentFeedbackMessage | null>(null);
  const [busyOrderId, setBusyOrderId] = useState<string | null>(null);
  const [cancelTargetOrderId, setCancelTargetOrderId] = useState<string | null>(null);

  const loadSeqRef = useRef(0);

  const loadAllPayments = useCallback(async (background = false) => {
    const seq = ++loadSeqRef.current;
    if (background) {
      setIsRefreshing(true);
    } else {
      setIsLoading(true);
    }
    setErrorMessage(null);

    try {
      const collected = await paymentsApi.listAllPayments();
      if (seq === loadSeqRef.current) {
        setPayments(collected);
      }
    } catch (error) {
      if (seq === loadSeqRef.current) {
        setErrorMessage(
          error instanceof ApiClientError
            ? error.message
            : 'Unable to fetch payment history.'
        );
      }
    } finally {
      if (seq === loadSeqRef.current) {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadAllPayments(false);
  }, [loadAllPayments]);

  const filteredPayments = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    return payments.filter((payment) => {
      if (statusFilter !== 'ALL' && payment.status !== statusFilter) {
        return false;
      }
      if (!query) {
        return true;
      }
      return (
        payment.paypalOrderId.toLowerCase().includes(query) ||
        payment.invoiceNumber.toLowerCase().includes(query) ||
        (payment.clientName?.toLowerCase().includes(query) ?? false) ||
        (payment.paypalCaptureId?.toLowerCase().includes(query) ?? false)
      );
    });
  }, [payments, statusFilter, searchQuery]);

  const handleCheckPaymentStatus = async (orderId: string) => {
    if (busyOrderId) {
      return;
    }
    setBusyOrderId(orderId);
    try {
      const result = await paymentsApi.captureOrder(orderId);
      setFeedback(describeCaptureOutcome(result));
      await loadAllPayments(true);
    } catch (error) {
      setFeedback(describePaymentFailure(error, orderId));
      await loadAllPayments(true);
    } finally {
      setBusyOrderId(null);
    }
  };

  const handleConfirmCancelOrder = async () => {
    if (!cancelTargetOrderId || busyOrderId) {
      return;
    }
    const orderId = cancelTargetOrderId;
    setBusyOrderId(orderId);
    try {
      const cancelled = await paymentsApi.cancelOrder(orderId, {
        reason: 'Cancelled from PayPilot payment history',
      });
      setCancelTargetOrderId(null);
      setFeedback({
        tone: 'info',
        title: 'Payment order cancelled',
        message: cancelled.paypalCancellationConfirmed
          ? `Order ${cancelled.paypalOrderId} cancellation confirmed.`
          : `Order ${cancelled.paypalOrderId} marked CANCELLED locally in PayPilot AI (remote PayPal cancellation was not separately confirmed).`,
      });
      await loadAllPayments(true);
    } catch (error) {
      setCancelTargetOrderId(null);
      setFeedback(describePaymentFailure(error, orderId));
      await loadAllPayments(true);
    } finally {
      setBusyOrderId(null);
    }
  };

  const handleResumeCheckout = (rawUrl: string) => {
    const safeUrl = parsePayPalSandboxApprovalUrl(rawUrl);
    if (!safeUrl) {
      setFeedback({
        tone: 'error',
        title: 'Blocked unsafe checkout URL',
        message: 'Approval URL did not pass strict HTTPS PayPal Sandbox validation.',
      });
      return;
    }
    window.location.assign(safeUrl);
  };

  return (
    <div className="space-y-5">
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-xl font-semibold tracking-tight text-slate-900">
                PayPal Sandbox Payments
              </h2>
              <span className="sandbox-badge">Sandbox Mode · Test Transactions Only</span>
            </div>
            <p className="mt-1 text-sm text-slate-600">
              All PayPal order and capture verification records across your invoices. Use{' '}
              <strong>Check Payment Status</strong> for any pending or interrupted captures instead of creating duplicate orders.
            </p>
          </div>

          <button
            type="button"
            onClick={() => void loadAllPayments(true)}
            disabled={isLoading || isRefreshing}
            className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <RefreshIcon className={`h-4 w-4 ${isRefreshing ? 'animate-spin' : ''}`} />
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
          </button>
        </div>

        <div className="mt-5 flex flex-col gap-3 border-t border-slate-100 pt-5 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter by payment status">
            {PAYMENT_STATUS_FILTERS.map((option) => {
              const active = statusFilter === option;
              return (
                <button
                  key={option}
                  type="button"
                  aria-pressed={active}
                  onClick={() => setStatusFilter(option)}
                  className={[
                    'rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-primary-500',
                    active
                      ? 'bg-slate-900 text-white'
                      : 'border border-slate-200 bg-slate-50 text-slate-600 hover:bg-slate-100 hover:text-slate-900',
                  ].join(' ')}
                >
                  {option}
                </button>
              );
            })}
          </div>

          <div className="w-full lg:w-80">
            <label htmlFor="payment-search" className="sr-only">
              Search payments
            </label>
            <input
              id="payment-search"
              type="search"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search order ID, capture ID, or invoice..."
              className="block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-primary-600 focus:outline-none focus:ring-2 focus:ring-primary-100"
            />
          </div>
        </div>
      </section>

      {errorMessage ? (
        <AlertBanner tone="error" title="Could not load payments" message={errorMessage} />
      ) : null}

      <PaymentFeedbackBanner
        feedback={feedback}
        busyOrderId={busyOrderId}
        onCheckStatus={(orderId) => void handleCheckPaymentStatus(orderId)}
      />

      {isLoading ? (
        <div className="rounded-xl border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
          Loading payment records...
        </div>
      ) : filteredPayments.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
          <h3 className="text-base font-semibold text-slate-900">
            {payments.length === 0 ? 'No PayPal Sandbox payments yet' : 'No matching payments found'}
          </h3>
          <p className="mx-auto mt-1 max-w-md text-sm text-slate-600">
            {payments.length === 0
              ? 'Approve a DRAFT invoice and select "Pay with PayPal Sandbox" to initiate your first test transaction.'
              : 'Try clearing the search query or switching to ALL payment statuses.'}
          </p>
          <div className="mt-5 flex justify-center gap-3">
            {payments.length > 0 ? (
              <button
                type="button"
                onClick={() => {
                  setStatusFilter('ALL');
                  setSearchQuery('');
                }}
                className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Reset Filters
              </button>
            ) : null}
            <Link
              to="/invoices"
              className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
            >
              View Invoices
            </Link>
          </div>
        </div>
      ) : (
        <PaymentHistoryTable
          payments={filteredPayments}
          showInvoiceColumn={true}
          busyOrderId={busyOrderId}
          onCheckStatus={(orderId) => void handleCheckPaymentStatus(orderId)}
          onCancelOrder={(orderId) => setCancelTargetOrderId(orderId)}
          onResumeCheckout={handleResumeCheckout}
        />
      )}

      <ConfirmDialog
        open={Boolean(cancelTargetOrderId)}
        title="Cancel Payment Order Locally?"
        description={
          cancelTargetOrderId
            ? `Cancel uncaptured order ${cancelTargetOrderId} locally in PayPilot AI?`
            : ''
        }
        confirmLabel={busyOrderId ? 'Cancelling...' : 'Confirm Local Cancel'}
        tone="danger"
        isBusy={Boolean(busyOrderId)}
        warningNote="PayPilot blocks cancellation once a capture attempt has started and does not assume remote PayPal cancellation without confirmation."
        onConfirm={() => void handleConfirmCancelOrder()}
        onCancel={() => setCancelTargetOrderId(null)}
      />
    </div>
  );
}
