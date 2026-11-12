import { InvoiceStatusBadge } from './InvoiceStatusBadge';
import type { InvoiceResponse } from '@/types';
import { formatDisplayDate, formatInvoiceMoney } from '@/utils/money';

export interface InvoiceListTableProps {
  invoices: InvoiceResponse[];
  onSelectInvoice: (invoiceId: number) => void;
}

export function InvoiceListTable({ invoices, onSelectInvoice }: InvoiceListTableProps) {
  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
      {/* Desktop table */}
      <div className="hidden overflow-x-auto md:block">
        <table className="min-w-full divide-y divide-slate-200 text-left text-sm">
          <thead className="bg-slate-50/90 text-xs font-semibold uppercase tracking-wider text-slate-500">
            <tr>
              <th scope="col" className="px-5 py-3.5">Invoice</th>
              <th scope="col" className="px-5 py-3.5">Client</th>
              <th scope="col" className="px-5 py-3.5">Amount</th>
              <th scope="col" className="px-5 py-3.5">Due Date</th>
              <th scope="col" className="px-5 py-3.5">Status</th>
              <th scope="col" className="px-5 py-3.5 text-right">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 bg-white">
            {invoices.map((invoice) => (
              <tr
                key={invoice.id}
                onClick={() => onSelectInvoice(invoice.id)}
                className="cursor-pointer transition-colors hover:bg-slate-50/80"
              >
                <td className="whitespace-nowrap px-5 py-4">
                  <div className="flex items-center gap-2">
                    <span className="font-semibold text-slate-900">
                      {invoice.invoiceNumber}
                    </span>
                    {invoice.aiExtracted ? (
                      <span className="ai-label" title="Draft originally extracted with AI">
                        AI Draft
                      </span>
                    ) : null}
                  </div>
                </td>
                <td className="px-5 py-4">
                  <p className="font-medium text-slate-900">{invoice.clientName}</p>
                  <p className="text-xs text-slate-500">{invoice.clientEmail}</p>
                </td>
                <td className="whitespace-nowrap px-5 py-4 font-semibold tabular-nums text-slate-900">
                  {formatInvoiceMoney(invoice.amount, invoice.currency)}
                </td>
                <td className="whitespace-nowrap px-5 py-4 text-slate-600">
                  {formatDisplayDate(invoice.dueDate)}
                </td>
                <td className="whitespace-nowrap px-5 py-4">
                  <InvoiceStatusBadge status={invoice.status} />
                </td>
                <td className="whitespace-nowrap px-5 py-4 text-right">
                  <button
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      onSelectInvoice(invoice.id);
                    }}
                    className="rounded-lg px-2.5 py-1.5 text-xs font-semibold text-primary-600 transition-colors hover:bg-primary-50 hover:text-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    Open
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Mobile card list */}
      <ul className="divide-y divide-slate-100 md:hidden">
        {invoices.map((invoice) => (
          <li key={invoice.id}>
            <button
              type="button"
              onClick={() => onSelectInvoice(invoice.id)}
              className="w-full px-4 py-4 text-left transition-colors hover:bg-slate-50 focus:outline-none focus:bg-slate-50"
            >
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-semibold text-slate-900">
                    {invoice.invoiceNumber}
                  </span>
                  {invoice.aiExtracted ? <span className="ai-label">AI Draft</span> : null}
                </div>
                <InvoiceStatusBadge status={invoice.status} />
              </div>

              <div className="mt-2 flex items-baseline justify-between gap-2">
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium text-slate-800">
                    {invoice.clientName}
                  </p>
                  <p className="truncate text-xs text-slate-500">{invoice.clientEmail}</p>
                </div>
                <p className="shrink-0 text-sm font-semibold tabular-nums text-slate-900">
                  {formatInvoiceMoney(invoice.amount, invoice.currency)}
                </p>
              </div>

              <p className="mt-2 text-xs text-slate-500">
                Due {formatDisplayDate(invoice.dueDate)}
              </p>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

