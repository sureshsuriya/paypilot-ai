import { PaymentStatusBadge } from '@/components/payments/PaymentStatusBadge';
import type { InvoiceResponse, PaymentResponse } from '@/types';
import { formatDisplayDate, formatInvoiceMoney } from '@/utils/money';
import { getTodayLocalIsoDate } from '@/utils/validation';

export interface InvoiceApprovalPanelProps {
  invoice: InvoiceResponse;
  payments: PaymentResponse[];
  isBusy: boolean;
  isRedirectingToPayPal: boolean;
  onStartEditDraft: () => void;
  onRequestDeleteDraft: () => void;
  onRequestApprove: () => void;
  onRequestEditApproved: () => void;
  onRequestRevokeApproval: () => void;
  onPayWithPayPalSandbox: () => void;
  onCheckPaymentStatus: (orderId: string) => void;
}

export function InvoiceApprovalPanel({
  invoice,
  payments,
  isBusy,
  isRedirectingToPayPal,
  onStartEditDraft,
  onRequestDeleteDraft,
  onRequestApprove,
  onRequestEditApproved,
  onRequestRevokeApproval,
  onPayWithPayPalSandbox,
  onCheckPaymentStatus,
}: InvoiceApprovalPanelProps) {
  const pendingPayment = payments.find((p) => p.status === 'PENDING');
  const capturedPayment = payments.find((p) => p.status === 'CAPTURED');
  const hasProtectedPayments = Boolean(pendingPayment || capturedPayment);
  const hasAnyPaymentRecords = payments.length > 0;
  const isDueDatePast = Boolean(invoice.dueDate && invoice.dueDate < getTodayLocalIsoDate());

  if (invoice.status === 'DRAFT') {
    return (
      <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="max-w-2xl space-y-1.5">
            <span className="inline-flex items-center rounded-md border border-purple-200 bg-purple-50 px-2.5 py-0.5 text-xs font-medium text-purple-800">
              Human Review Required Before Payment
            </span>
            <h3 className="text-lg font-semibold text-slate-900">
              Review &amp; Approve Invoice Snapshot
            </h3>
            <p className="text-sm leading-relaxed text-slate-600">
              Review all client details, description, due date, and{' '}
              <strong className="font-semibold text-slate-900">
                {formatInvoiceMoney(invoice.amount, invoice.currency)}
              </strong>{' '}
              total. Approving locks these terms against the server SHA-256 snapshot and unlocks PayPal Sandbox checkout without automatically creating an order.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2.5">
            {!hasProtectedPayments ? (
              <button
                type="button"
                disabled={isBusy}
                onClick={onStartEditDraft}
                className="rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
              >
                Edit Draft
              </button>
            ) : null}

            {!hasAnyPaymentRecords ? (
              <button
                type="button"
                disabled={isBusy}
                onClick={onRequestDeleteDraft}
                className="rounded-lg border border-red-200 bg-white px-3.5 py-2 text-sm font-medium text-red-700 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-60"
              >
                Delete Draft
              </button>
            ) : null}

            {!hasProtectedPayments ? (
              <button
                type="button"
                disabled={isBusy || isDueDatePast}
                onClick={onRequestApprove}
                className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Approve Invoice
              </button>
            ) : null}
          </div>
        </div>

        <div className="mt-4 grid gap-3 rounded-xl border border-slate-200 bg-slate-50/70 p-4 sm:grid-cols-2">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              Current Server Snapshot Hash (SHA-256)
            </p>
            <p className="mt-1 break-all font-mono text-xs text-slate-800">
              {invoice.snapshotHash}
            </p>
          </div>
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              Approval Eligibility
            </p>
            <p className="mt-1 text-xs text-slate-700">
              {hasProtectedPayments
                ? 'Locked because a payment capture is already pending or completed.'
                : isDueDatePast
                  ? 'Due date is in the past — edit the invoice to a today or future date before approving.'
                  : 'Verified required fields present. Ready for explicit human approval.'}
            </p>
          </div>
        </div>

        {hasAnyPaymentRecords && !hasProtectedPayments ? (
          <p className="mt-3 text-xs text-slate-500">
            Note: Draft deletion is disabled because historical payment order records exist for audit integrity.
          </p>
        ) : null}
      </section>
    );
  }

  if (invoice.status === 'APPROVED') {
    return (
      <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex flex-col justify-between gap-4 lg:flex-row lg:items-start">
          <div className="max-w-2xl space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center rounded-md border border-primary-100 bg-primary-50 px-2.5 py-0.5 text-xs font-semibold text-primary-700">
                Snapshot Approved
              </span>
              <span className="sandbox-badge">
                PayPal Sandbox Mode · Test Environment Only
              </span>
            </div>

            <h3 className="text-lg font-semibold text-slate-900">
              Ready for PayPal Sandbox Checkout
            </h3>

            <p className="text-sm leading-relaxed text-slate-600">
              Authorized amount:{' '}
              <strong className="font-semibold text-slate-900">
                {formatInvoiceMoney(invoice.amount, invoice.currency)}
              </strong>
              . Checkout occurs in PayPal Sandbox using test accounts only — real money is never charged.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2.5">
            {!hasProtectedPayments ? (
              <>
                <button
                  type="button"
                  disabled={isBusy}
                  onClick={onRequestEditApproved}
                  className="rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  Edit Invoice
                </button>
                <button
                  type="button"
                  disabled={isBusy}
                  onClick={onRequestRevokeApproval}
                  className="rounded-lg border border-amber-300 bg-amber-50/70 px-3.5 py-2 text-sm font-medium text-amber-900 transition-colors hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  Revoke Approval
                </button>
                <button
                  type="button"
                  disabled={isBusy || isRedirectingToPayPal}
                  onClick={onPayWithPayPalSandbox}
                  className="inline-flex items-center justify-center rounded-lg bg-primary-600 px-5 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {isRedirectingToPayPal
                    ? 'Opening PayPal Sandbox...'
                    : 'Pay with PayPal Sandbox'}
                </button>
              </>
            ) : pendingPayment ? (
              <button
                type="button"
                disabled={isBusy}
                onClick={() => onCheckPaymentStatus(pendingPayment.paypalOrderId)}
                className="rounded-lg bg-amber-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-amber-700 disabled:cursor-not-allowed disabled:opacity-60"
              >
                Check Payment Status
              </button>
            ) : null}
          </div>
        </div>

        {pendingPayment ? (
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50/80 p-4 text-xs text-amber-900">
            <div className="space-y-1">
              <div className="flex items-center gap-2 font-semibold">
                <PaymentStatusBadge status={pendingPayment.status} />
                <span>Order {pendingPayment.paypalOrderId} is awaiting PayPal confirmation</span>
              </div>
              <p>
                Editing, approval revocation, and duplicate order creation are disabled until PayPal Sandbox confirms settlement or declines the pending capture.
              </p>
            </div>
          </div>
        ) : null}

        <dl className="mt-4 grid gap-3 rounded-xl border border-slate-200 bg-slate-50/70 p-4 text-xs sm:grid-cols-3">
          <div>
            <dt className="font-semibold uppercase tracking-wider text-slate-500">Approved At</dt>
            <dd className="mt-1 font-medium text-slate-800">
              {formatDisplayDate(invoice.approvedAt)}
            </dd>
          </div>
          <div>
            <dt className="font-semibold uppercase tracking-wider text-slate-500">Approved By User ID</dt>
            <dd className="mt-1 font-mono text-slate-800">
              {invoice.approvedByUserId ?? '—'}
            </dd>
          </div>
          <div>
            <dt className="font-semibold uppercase tracking-wider text-slate-500">Approved Snapshot Hash</dt>
            <dd className="mt-1 break-all font-mono text-slate-800">
              {invoice.approvedSnapshotHash ?? invoice.snapshotHash}
            </dd>
          </div>
        </dl>
      </section>
    );
  }

  if (invoice.status === 'PAID') {
    return (
      <section className="rounded-2xl border border-emerald-200 bg-emerald-50/60 p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <span className="inline-flex items-center rounded-md border border-emerald-200 bg-white px-2.5 py-0.5 text-xs font-semibold text-emerald-800">
              Verified Settlement
            </span>
            <h3 className="mt-2 text-lg font-semibold text-emerald-950">
              Invoice Paid via PayPal Sandbox
            </h3>
            <p className="mt-1 text-sm text-emerald-900/90">
              Verified payment of{' '}
              <strong className="font-semibold">
                {formatInvoiceMoney(invoice.amount, invoice.currency)}
              </strong>{' '}
              settled on {formatDisplayDate(invoice.paidAt)}. Paid invoices are immutable for audit compliance.
            </p>
          </div>
          <span className="sandbox-badge">PayPal Sandbox</span>
        </div>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <h3 className="text-base font-semibold text-slate-900">
        Invoice Status: {invoice.status}
      </h3>
      <p className="mt-1 text-sm text-slate-600">
        Editing, approval, and payment actions are unavailable while this invoice is in{' '}
        <span className="font-semibold">{invoice.status}</span> status.
      </p>
    </section>
  );
}

