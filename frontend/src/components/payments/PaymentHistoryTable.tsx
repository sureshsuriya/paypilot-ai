import { Link } from 'react-router-dom';
import { PaymentStatusBadge } from './PaymentStatusBadge';
import type { PaymentResponse } from '@/types';
import { formatDisplayDate, formatInvoiceMoney } from '@/utils/money';
import { parsePayPalSandboxApprovalUrl } from '@/utils/paypal';

export interface PaymentHistoryTableProps {
  payments: PaymentResponse[];
  showInvoiceColumn?: boolean;
  busyOrderId?: string | null;
  onCheckStatus: (orderId: string) => void;
  onCancelOrder?: (orderId: string) => void;
  onResumeCheckout?: (approvalUrl: string) => void;
}

function renderStatusNote(payment: PaymentResponse): string | null {
  if (payment.status === 'PENDING') {
    const reason = payment.pendingReason ? `Reason: ${payment.pendingReason}. ` : '';
    return `${reason}PayPal capture is pending confirmation — invoice is NOT marked paid yet.`;
  }
  if (payment.status === 'CANCELLED') {
    if (payment.paypalCancellationConfirmed) {
      return 'Order cancellation confirmed remotely by PayPal Sandbox.';
    }
    if (payment.locallyCancelled) {
      return 'Cancelled locally in PayPilot AI (remote PayPal cancellation not confirmed).';
    }
    return 'Cancelled locally.';
  }
  if (payment.status === 'FAILED') {
    const failure = typeof payment.summary?.verificationFailure === 'string'
      ? payment.summary.verificationFailure
      : null;
    return failure ? `Failure detail: ${failure}` : 'Payment capture failed or declined.';
  }
  return null;
}

export function PaymentHistoryTable({
  payments,
  showInvoiceColumn = false,
  busyOrderId = null,
  onCheckStatus,
  onCancelOrder,
  onResumeCheckout,
}: PaymentHistoryTableProps) {
  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
      <div className="divide-y divide-slate-100">
        {payments.map((payment) => {
          const isBusy = busyOrderId === payment.paypalOrderId;
          const note = renderStatusNote(payment);
          const validatedApprovalUrl = parsePayPalSandboxApprovalUrl(payment.approvalUrl);
          const canCheckStatus =
            payment.status === 'PENDING' ||
            payment.status === 'APPROVED' ||
            payment.status === 'CREATED';
          const canCancelLocally =
            Boolean(onCancelOrder) &&
            (payment.status === 'CREATED' || payment.status === 'APPROVED');
          const canResumeCheckout =
            Boolean(onResumeCheckout) &&
            Boolean(validatedApprovalUrl) &&
            (payment.status === 'CREATED' || payment.status === 'APPROVED');

          return (
            <div key={payment.id} className="p-4 sm:p-5">
              <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-start">
                <div className="space-y-1.5">
                  <div className="flex flex-wrap items-center gap-2">
                    <PaymentStatusBadge status={payment.status} />
                    <span className="sandbox-badge">PayPal Sandbox</span>
                    <span className="inline-flex items-center rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 text-[11px] font-medium text-slate-700">
                      {payment.customerLinkCheckout ? 'Customer Link' : 'Seller Portal'}
                    </span>
                    {showInvoiceColumn ? (
                      <Link
                        to={`/invoices/${payment.invoiceId}`}
                        className="text-xs font-semibold text-primary-600 hover:text-primary-700 hover:underline"
                      >
                        {payment.invoiceNumber}
                      </Link>
                    ) : null}
                    {showInvoiceColumn && payment.clientName ? (
                      <span className="text-xs text-slate-500">· {payment.clientName}</span>
                    ) : null}
                  </div>

                  <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
                    <p className="text-base font-semibold tabular-nums text-slate-900">
                      {formatInvoiceMoney(payment.amount, payment.currency)}
                    </p>
                    <p className="font-mono text-xs text-slate-600">
                      Order: <span className="font-semibold text-slate-800">{payment.paypalOrderId}</span>
                    </p>
                    {payment.paypalCaptureId ? (
                      <p className="font-mono text-xs text-slate-600">
                        Capture: <span className="font-semibold text-slate-800">{payment.paypalCaptureId}</span>
                      </p>
                    ) : null}
                  </div>

                  <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500">
                    <span>Created {formatDisplayDate(payment.createdAt)}</span>
                    {payment.capturedAt ? (
                      <span>Captured {formatDisplayDate(payment.capturedAt)}</span>
                    ) : null}
                  </div>

                  {note ? (
                    <p
                      className={[
                        'mt-1.5 text-xs leading-relaxed',
                        payment.status === 'PENDING'
                          ? 'font-medium text-amber-800'
                          : payment.status === 'FAILED'
                            ? 'text-red-700'
                            : 'text-slate-600',
                      ].join(' ')}
                    >
                      {note}
                    </p>
                  ) : null}
                </div>

                <div className="flex flex-wrap items-center gap-2 sm:justify-end">
                  {canResumeCheckout && validatedApprovalUrl ? (
                    <button
                      type="button"
                      disabled={isBusy}
                      onClick={() => onResumeCheckout?.(validatedApprovalUrl)}
                      className="rounded-lg bg-primary-600 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      Continue Checkout
                    </button>
                  ) : null}

                  {canCheckStatus ? (
                    <button
                      type="button"
                      disabled={isBusy}
                      onClick={() => onCheckStatus(payment.paypalOrderId)}
                      className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      {isBusy ? 'Checking...' : 'Check Payment Status'}
                    </button>
                  ) : null}

                  {canCancelLocally ? (
                    <button
                      type="button"
                      disabled={isBusy}
                      onClick={() => onCancelOrder?.(payment.paypalOrderId)}
                      className="rounded-lg border border-red-200 bg-white px-3 py-1.5 text-xs font-semibold text-red-700 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      Cancel Order
                    </button>
                  ) : null}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

