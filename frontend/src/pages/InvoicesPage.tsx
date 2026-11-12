import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { invoicesApi } from '@/api';
import { InvoiceListTable } from '@/components/invoices/InvoiceListTable';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { RefreshIcon } from '@/components/ui/Icons';
import { ApiClientError, type InvoiceResponse, type InvoiceStatus } from '@/types';

type StatusFilterOption = 'ALL' | InvoiceStatus;

const STATUS_OPTIONS: StatusFilterOption[] = [
  'ALL',
  'DRAFT',
  'APPROVED',
  'PAID',
];

export function InvoicesPage() {
  const navigate = useNavigate();

  const [invoices, setInvoices] = useState<InvoiceResponse[]>([]);
  const [statusFilter, setStatusFilter] = useState<StatusFilterOption>('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const latestRequestId = useRef(0);

  const loadInvoices = useCallback(
    async (selectedStatus: StatusFilterOption, backgroundRefresh = false) => {
      const requestId = ++latestRequestId.current;
      if (backgroundRefresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setErrorMessage(null);

      try {
        const data = await invoicesApi.listInvoices(
          selectedStatus === 'ALL' ? undefined : selectedStatus
        );
        if (requestId === latestRequestId.current) {
          setInvoices(data);
        }
      } catch (error) {
        if (requestId === latestRequestId.current) {
          setErrorMessage(
            error instanceof ApiClientError
              ? error.message
              : 'Unable to fetch invoices from the backend.'
          );
        }
      } finally {
        if (requestId === latestRequestId.current) {
          setIsLoading(false);
          setIsRefreshing(false);
        }
      }
    },
    []
  );

  useEffect(() => {
    void loadInvoices(statusFilter, false);
  }, [statusFilter, loadInvoices]);

  const filteredInvoices = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    if (!query) {
      return invoices;
    }
    return invoices.filter(
      (invoice) =>
        invoice.invoiceNumber.toLowerCase().includes(query) ||
        invoice.clientName.toLowerCase().includes(query) ||
        invoice.clientEmail.toLowerCase().includes(query)
    );
  }, [invoices, searchQuery]);

  const hasActiveFilters = statusFilter !== 'ALL' || searchQuery.trim().length > 0;

  return (
    <div className="space-y-5">
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
          <div>
            <h2 className="text-xl font-semibold tracking-tight text-slate-900">
              Invoice Ledger
            </h2>
            <p className="mt-0.5 text-sm text-slate-600">
              Search by invoice number, client name, or client email, or filter by lifecycle status.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2.5">
            <button
              type="button"
              onClick={() => void loadInvoices(statusFilter, true)}
              disabled={isLoading || isRefreshing}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <RefreshIcon className={`h-4 w-4 ${isRefreshing ? 'animate-spin' : ''}`} />
              <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
            </button>

            <Link
              to="/invoices/create"
              className="inline-flex items-center justify-center rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2"
            >
              Create Invoice
            </Link>
          </div>
        </div>

        <div className="mt-5 flex flex-col gap-3 border-t border-slate-100 pt-5 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter by invoice status">
            {STATUS_OPTIONS.map((option) => {
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
            <label htmlFor="invoice-search" className="sr-only">
              Search invoices
            </label>
            <input
              id="invoice-search"
              type="search"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search number, client name, or email..."
              className="block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-primary-600 focus:outline-none focus:ring-2 focus:ring-primary-100"
            />
          </div>
        </div>
      </section>

      {errorMessage ? (
        <AlertBanner
          tone="error"
          title="Failed to load invoices"
          message={errorMessage}
        />
      ) : null}

      {isLoading ? (
        <div className="rounded-xl border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
          Loading invoices...
        </div>
      ) : filteredInvoices.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
          <h3 className="text-base font-semibold text-slate-900">
            {hasActiveFilters ? 'No matching invoices found' : 'No invoices created yet'}
          </h3>
          <p className="mx-auto mt-1 max-w-md text-sm text-slate-600">
            {hasActiveFilters
              ? 'Try clearing your search term or switching back to ALL statuses.'
              : 'Create a new invoice manually or draft one from plain language using AI extraction.'}
          </p>
          <div className="mt-5 flex justify-center gap-3">
            {hasActiveFilters ? (
              <button
                type="button"
                onClick={() => {
                  setStatusFilter('ALL');
                  setSearchQuery('');
                }}
                className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Clear Filters
              </button>
            ) : null}
            <Link
              to="/invoices/create"
              className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
            >
              Create Invoice
            </Link>
          </div>
        </div>
      ) : (
        <InvoiceListTable
          invoices={filteredInvoices}
          onSelectInvoice={(invoiceId) => navigate(`/invoices/${invoiceId}`)}
        />
      )}
    </div>
  );
}
