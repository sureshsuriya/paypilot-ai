import { PaymentStatusBadge } from '@/components/payments/PaymentStatusBadge';
import { SandboxIndicator } from '@/components/layout/SandboxIndicator';
import { ShieldCheckIcon } from '@/components/ui/Icons';
import type { PublicPaymentReceiptResponse } from '@/types';
import { formatDisplayDate, formatInvoiceMoney } from '@/utils/money';

export interface CustomerReceiptCardProps {
  receipt: PublicPaymentReceiptResponse;
}

export function CustomerReceiptCard({ receipt }: CustomerReceiptCardProps) {
  const handlePrintReceipt = () => {
    window.print();
  };

  return (
    <section
      aria-label="Verified payment receipt"
      className="overflow-hidden rounded-2xl border border-emerald-200 bg-white shadow-sm"
    >
      <div className="border-b border-emerald-200/70 bg-emerald-50/80 px-6 py-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <span className="mt-0.5 inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-700">
              <ShieldCheckIcon className="h-5 w-5" />
            </span>
            <div>
              <h1 className="text-xl font-bold tracking-tight text-emerald-950">
                Payment Confirmed
              </h1>
              <p className="mt-1 text-sm text-emerald-900/90">
                PayPilot AI verified this PayPal Sandbox payment capture and marked the invoice PAID.
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <PaymentStatusBadge status={receipt.paymentStatus} />
            {receipt.sandboxMode ? <SandboxIndicator variant="compact" /> : null}
          </div>
        </div>
      </div>

      <div className="p-6">
        <div className="flex flex-wrap items-baseline justify-between gap-3 rounded-xl border border-slate-200 bg-slate-50/70 px-4 py-4">
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Amount Paid
            </p>
            <p className="mt-1 text-2xl font-bold tracking-tight text-slate-900">
              {formatInvoiceMoney(receipt.amount, receipt.currency)}
            </p>
          </div>
          <div className="text-left sm:text-right">
            <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Paid On
            </p>
            <p className="mt-1 text-sm font-semibold text-slate-800">
              {formatDisplayDate(receipt.paidAt)}
            </p>
          </div>
        </div>

        <dl className="mt-6 grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Invoice Number
            </dt>
            <dd className="mt-1 font-mono font-semibold text-slate-900">
              {receipt.invoiceNumber}
            </dd>
          </div>

          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Billed By
            </dt>
            <dd className="mt-1 font-medium text-slate-900">
              {receipt.sellerDisplayName}
            </dd>
          </div>

          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Billed To
            </dt>
            <dd className="mt-1 font-medium text-slate-900">
              {receipt.customerName}
            </dd>
          </div>

          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Currency
            </dt>
            <dd className="mt-1 font-medium text-slate-900">{receipt.currency}</dd>
          </div>

          <div className="sm:col-span-2">
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Description
            </dt>
            <dd className="mt-1 whitespace-pre-line text-slate-800">
              {receipt.description}
            </dd>
          </div>

          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              PayPal Order Reference
            </dt>
            <dd className="mt-1 break-all font-mono text-xs font-semibold text-slate-800">
              {receipt.paypalOrderId}
            </dd>
          </div>

          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              PayPal Capture Reference
            </dt>
            <dd className="mt-1 break-all font-mono text-xs font-semibold text-slate-800">
              {receipt.paypalCaptureId}
            </dd>
          </div>
        </dl>

        <div className="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 pt-5 print:hidden">
          <p className="text-xs text-slate-500">
            Save or print this confirmation for your records. No real funds were charged (Sandbox mode).
          </p>
          <button
            type="button"
            onClick={handlePrintReceipt}
            className="inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-xs font-semibold text-slate-700 shadow-sm transition hover:bg-slate-50"
          >
            Print Receipt
          </button>
        </div>
      </div>
    </section>
  );
}

