import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { invoicesApi, paymentsApi } from '@/api';
import { InvoiceListTable } from '@/components/invoices/InvoiceListTable';
import { SummaryMetricCard } from '@/components/invoices/SummaryMetricCard';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { RefreshIcon } from '@/components/ui/Icons';
import { useAuth } from '@/hooks/useAuth';
import {
  ApiClientError,
  type InvoiceResponse,
  type PaymentResponse,
} from '@/types';
import {
  summarizeCapturedRevenueByCurrency,
  summarizeOutstandingByCurrency,
  type CurrencySubtotal,
} from '@/utils/money';

const RECENT_INVOICE_LIMIT = 6;

function renderCurrencyList(subtotals: CurrencySubtotal[], emptyLabel: string) {
  if (subtotals.length === 0) {
    return emptyLabel;
  }
  if (subtotals.length === 1) {
    return subtotals[0].formatted;
  }
  return (
    <div className="space-y-0.5 text-lg leading-snug">
      {subtotals.map((item) => (
        <div key={item.currency} className="tabular-nums">
          {item.formatted}
        </div>
      ))}
    </div>
  );
}

export function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [invoices, setInvoices] = useState<InvoiceResponse[]>([]);
  const [payments, setPayments] = useState<PaymentResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const loadDashboardData = useCallback(async (backgroundRefresh = false) => {
    if (backgroundRefresh) {
      setIsRefreshing(true);
    } else {
      setIsLoading(true);
    }
    setErrorMessage(null);

    try {
      const [invoiceList, paymentList] = await Promise.all([
        invoicesApi.listInvoices(),
        paymentsApi.listAllPayments(),
      ]);
      setInvoices(invoiceList);
      setPayments(paymentList);
    } catch (error) {
      setErrorMessage(
        error instanceof ApiClientError
          ? error.message
          : 'Unable to load dashboard metrics from the backend.'
      );
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void loadDashboardData(false);
  }, [loadDashboardData]);

  const metrics = useMemo(() => {
    let draftCount = 0;
    let approvedCount = 0;
    let paidCount = 0;

    for (const invoice of invoices) {
      if (invoice.status === 'DRAFT') {
        draftCount += 1;
      } else if (invoice.status === 'APPROVED') {
        approvedCount += 1;
      } else if (invoice.status === 'PAID') {
        paidCount += 1;
      }
    }

    let pendingVerificationCount = 0;
    let awaitingCustomerCheckoutCount = 0;
    let capturedCount = 0;
    let cancelledOrFailedCount = 0;
    let customerLinkCapturedCount = 0;

    for (const payment of payments) {
      if (payment.status === 'PENDING') {
        pendingVerificationCount += 1;
      } else if (payment.status === 'CREATED' || payment.status === 'APPROVED') {
        awaitingCustomerCheckoutCount += 1;
      } else if (payment.status === 'CAPTURED') {
        capturedCount += 1;
        if (payment.customerLinkCheckout) {
          customerLinkCapturedCount += 1;
        }
      } else if (payment.status === 'CANCELLED' || payment.status === 'FAILED') {
        cancelledOrFailedCount += 1;
      }
    }

    const collectedRevenueByCurrency = summarizeCapturedRevenueByCurrency(payments);
    const outstandingByCurrency = summarizeOutstandingByCurrency(invoices);

    const total = invoices.length;
    const paidPct = total > 0 ? Math.round((paidCount / total) * 100) : 0;
    const approvedPct = total > 0 ? Math.round((approvedCount / total) * 100) : 0;
    const draftPct = total > 0 ? Math.max(0, 100 - paidPct - approvedPct) : 0;

    return {
      totalCount: total,
      draftCount,
      approvedCount,
      paidCount,
      pendingPaymentsCount: pendingVerificationCount + awaitingCustomerCheckoutCount,
      pendingVerificationCount,
      awaitingCustomerCheckoutCount,
      capturedCount,
      cancelledOrFailedCount,
      customerLinkCapturedCount,
      collectedRevenueByCurrency,
      outstandingByCurrency,
      paidPct,
      approvedPct,
      draftPct,
    };
  }, [invoices, payments]);

  const recentInvoices = useMemo(
    () => invoices.slice(0, RECENT_INVOICE_LIMIT),
    [invoices]
  );

  return (
    <div className="space-y-6">
      <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-primary-600">
              Seller Operations Overview
            </p>
            <h2 className="mt-1 text-2xl font-semibold tracking-tight text-slate-900">
              Welcome back, {user?.name ?? 'User'}
            </h2>
            <p className="mt-1 text-sm text-slate-600">
              Live invoice metrics, pending PayPal Sandbox payments, and verified revenue calculated directly from backend records.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2.5">
            <button
              type="button"
              onClick={() => void loadDashboardData(true)}
              disabled={isLoading || isRefreshing}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <RefreshIcon className={`h-4 w-4 ${isRefreshing ? 'animate-spin' : ''}`} />
              <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
            </button>

            <Link
              to="/customers"
              className="inline-flex items-center justify-center rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50"
            >
              Customers
            </Link>

            <Link
              to="/invoices/create"
              className="inline-flex items-center justify-center rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2"
            >
              Create Invoice
            </Link>
          </div>
        </div>
      </section>

      {errorMessage ? (
        <AlertBanner
          tone="error"
          title="Could not load dashboard metrics"
          message={errorMessage}
        />
      ) : null}

      {isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
          {Array.from({ length: 5 }).map((_, idx) => (
            <div
              key={idx}
              className="h-28 animate-pulse rounded-xl border border-slate-200 bg-white p-5"
            >
              <div className="h-3 w-24 rounded bg-slate-200" />
              <div className="mt-4 h-7 w-16 rounded bg-slate-200" />
              <div className="mt-3 h-3 w-32 rounded bg-slate-100" />
            </div>
          ))}
        </div>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <SummaryMetricCard
              label="Total Invoices"
              value={metrics.totalCount}
              subtext="All owned records"
              accent="neutral"
            />
            <SummaryMetricCard
              label="Draft Invoices"
              value={metrics.draftCount}
              subtext="Awaiting review & approval"
              accent="purple"
            />
            <SummaryMetricCard
              label="Approved"
              value={metrics.approvedCount}
              subtext="Ready for payment links"
              accent="blue"
            />
            <SummaryMetricCard
              label="Paid Invoices"
              value={metrics.paidCount}
              subtext="Verified PayPal capture"
              accent="emerald"
            />
            <SummaryMetricCard
              label="Pending Payments"
              value={metrics.pendingPaymentsCount}
              subtext={
                metrics.pendingVerificationCount > 0
                  ? `${metrics.pendingVerificationCount} awaiting capture settlement`
                  : metrics.awaitingCustomerCheckoutCount > 0
                    ? `${metrics.awaitingCustomerCheckoutCount} open PayPal orders`
                    : 'No uncaptured orders'
              }
              accent="purple"
            />
          </div>

          <section
            aria-label="Revenue Overview"
            className="grid gap-4 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm lg:grid-cols-3"
          >
            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                Revenue Overview · Verified Collected
              </p>
              <div className="mt-2 text-2xl font-semibold tracking-tight text-emerald-700 tabular-nums">
                {renderCurrencyList(metrics.collectedRevenueByCurrency, '$0.00')}
              </div>
              <p className="mt-1.5 text-xs text-slate-500">
                {metrics.capturedCount} verified PayPal capture
                {metrics.capturedCount === 1 ? '' : 's'}
                {metrics.customerLinkCapturedCount > 0
                  ? ` (${metrics.customerLinkCapturedCount} via Customer Portal links)`
                  : ''}
              </p>
            </div>

            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                Outstanding Open Balance
              </p>
              <div className="mt-2 text-2xl font-semibold tracking-tight text-slate-900 tabular-nums">
                {renderCurrencyList(metrics.outstandingByCurrency, '$0.00')}
              </div>
              <p className="mt-1.5 text-xs text-slate-500">
                Across DRAFT &amp; APPROVED invoices awaiting payment
              </p>
            </div>

            <div className="flex flex-col justify-between">
              <div>
                <div className="flex items-center justify-between text-xs">
                  <span className="font-semibold uppercase tracking-wider text-slate-500">
                    Invoice Pipeline
                  </span>
                  <Link
                    to="/payments"
                    className="font-medium text-primary-600 hover:underline"
                  >
                    Payment Ledger ({payments.length})
                  </Link>
                </div>

                <div
                  className="mt-3 flex h-3 w-full overflow-hidden rounded-full bg-slate-100"
                  role="img"
                  aria-label="Invoice status breakdown bar"
                >
                  {metrics.paidPct > 0 ? (
                    <div
                      className="bg-emerald-500"
                      style={{ width: `${metrics.paidPct}%` }}
                      title={`Paid: ${metrics.paidCount}`}
                    />
                  ) : null}
                  {metrics.approvedPct > 0 ? (
                    <div
                      className="bg-primary-600"
                      style={{ width: `${metrics.approvedPct}%` }}
                      title={`Approved: ${metrics.approvedCount}`}
                    />
                  ) : null}
                  {metrics.draftPct > 0 ? (
                    <div
                      className="bg-purple-400"
                      style={{ width: `${metrics.draftPct}%` }}
                      title={`Draft: ${metrics.draftCount}`}
                    />
                  ) : null}
                </div>
              </div>

              <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600">
                <span className="inline-flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-emerald-500" />
                  Paid ({metrics.paidCount})
                </span>
                <span className="inline-flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-primary-600" />
                  Approved ({metrics.approvedCount})
                </span>
                <span className="inline-flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-purple-400" />
                  Draft ({metrics.draftCount})
                </span>
                {metrics.cancelledOrFailedCount > 0 ? (
                  <span className="text-slate-500">
                    · {metrics.cancelledOrFailedCount} cancelled/failed orders
                  </span>
                ) : null}
              </div>
            </div>
          </section>
        </>
      )}

      <section className="space-y-3">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">Recent Invoice Activity</h3>
            <p className="text-xs text-slate-500">
              Select any invoice to approve terms, generate a customer payment link, or track payments.
            </p>
          </div>
          {invoices.length > 0 ? (
            <Link
              to="/invoices"
              className="text-sm font-medium text-primary-600 hover:text-primary-700"
            >
              View all ({invoices.length})
            </Link>
          ) : null}
        </div>

        {isLoading ? (
          <div className="rounded-xl border border-slate-200 bg-white p-8 text-center text-sm text-slate-500">
            Loading recent invoices...
          </div>
        ) : recentInvoices.length === 0 ? (
          <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
            <h4 className="text-base font-semibold text-slate-900">No invoices created yet</h4>
            <p className="mx-auto mt-1 max-w-md text-sm text-slate-600">
              Start by creating a manual invoice or extracting a draft from natural language with Gemini AI review.
            </p>
            <div className="mt-5 flex justify-center">
              <Link
                to="/invoices/create"
                className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-primary-700"
              >
                Create Your First Invoice
              </Link>
            </div>
          </div>
        ) : (
          <InvoiceListTable
            invoices={recentInvoices}
            onSelectInvoice={(invoiceId) => navigate(`/invoices/${invoiceId}`)}
          />
        )}
      </section>
    </div>
  );
}
