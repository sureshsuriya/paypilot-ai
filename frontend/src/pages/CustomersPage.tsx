import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { customersApi } from '@/api';
import { InvoiceStatusBadge } from '@/components/invoices/InvoiceStatusBadge';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { FormField } from '@/components/ui/FormField';
import { CloseIcon, RefreshIcon, UsersIcon } from '@/components/ui/Icons';
import {
  ApiClientError,
  type CustomerDetailResponse,
  type CustomerResponse,
} from '@/types';
import { formatDisplayDate, formatInvoiceMoney } from '@/utils/money';
import {
  normalizeAndValidateOptionalPhone,
  validateCustomerForm,
  type CustomerFormErrors,
  type CustomerFormValues,
} from '@/utils/validation';

const EMPTY_CUSTOMER_FORM: CustomerFormValues = {
  name: '',
  email: '',
  phone: '',
  company: '',
  notes: '',
};

function customerToFormValues(customer: CustomerResponse): CustomerFormValues {
  return {
    name: customer.name,
    email: customer.email,
    phone: customer.phone ?? '',
    company: customer.company ?? '',
    notes: customer.notes ?? '',
  };
}

export function CustomersPage() {
  const navigate = useNavigate();

  const [customers, setCustomers] = useState<CustomerResponse[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [onlyMissingPhone, setOnlyMissingPhone] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [bannerMessage, setBannerMessage] = useState<string | null>(null);

  const [formModalMode, setFormModalMode] = useState<'create' | 'edit' | null>(null);
  const [editingCustomer, setEditingCustomer] = useState<CustomerResponse | null>(null);
  const [formValues, setFormValues] = useState<CustomerFormValues>(EMPTY_CUSTOMER_FORM);
  const [formErrors, setFormErrors] = useState<CustomerFormErrors>({});
  const [formSubmitError, setFormSubmitError] = useState<string | null>(null);
  const [isSavingForm, setIsSavingForm] = useState(false);

  const [detailModal, setDetailModal] = useState<CustomerDetailResponse | null>(null);
  const [isLoadingDetail, setIsLoadingDetail] = useState(false);

  const loadSeqRef = useRef(0);

  const loadCustomers = useCallback(async (background = false) => {
    const seq = ++loadSeqRef.current;
    if (background) {
      setIsRefreshing(true);
    } else {
      setIsLoading(true);
    }
    setErrorMessage(null);

    try {
      const data = await customersApi.listCustomers();
      if (seq === loadSeqRef.current) {
        setCustomers(data);
      }
    } catch (error) {
      if (seq === loadSeqRef.current) {
        setErrorMessage(
          error instanceof ApiClientError
            ? error.message
            : 'Unable to load customers from the backend.'
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
    void loadCustomers(false);
  }, [loadCustomers]);

  const filteredCustomers = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    return customers.filter((customer) => {
      if (onlyMissingPhone && customer.phone) {
        return false;
      }
      if (!query) {
        return true;
      }
      return (
        customer.name.toLowerCase().includes(query) ||
        customer.email.toLowerCase().includes(query) ||
        (customer.phone?.toLowerCase().includes(query) ?? false) ||
        (customer.company?.toLowerCase().includes(query) ?? false)
      );
    });
  }, [customers, searchQuery, onlyMissingPhone]);

  const missingPhoneCount = useMemo(
    () => customers.filter((c) => !c.phone).length,
    [customers]
  );

  const openCreateModal = () => {
    setFormModalMode('create');
    setEditingCustomer(null);
    setFormValues(EMPTY_CUSTOMER_FORM);
    setFormErrors({});
    setFormSubmitError(null);
  };

  const openEditModal = (customer: CustomerResponse) => {
    setFormModalMode('edit');
    setEditingCustomer(customer);
    setFormValues(customerToFormValues(customer));
    setFormErrors({});
    setFormSubmitError(null);
  };

  const closeFormModal = () => {
    if (isSavingForm) {
      return;
    }
    setFormModalMode(null);
    setEditingCustomer(null);
  };

  const handleOpenCustomerDetail = async (customerId: number) => {
    setIsLoadingDetail(true);
    setErrorMessage(null);
    try {
      const detail = await customersApi.getCustomer(customerId);
      setDetailModal(detail);
    } catch (error) {
      setErrorMessage(
        error instanceof ApiClientError ? error.message : 'Unable to load customer details.'
      );
    } finally {
      setIsLoadingDetail(false);
    }
  };

  const handleSubmitForm = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isSavingForm) {
      return;
    }

    const errors = validateCustomerForm(formValues);
    if (Object.keys(errors).length > 0) {
      setFormErrors(errors);
      return;
    }

    setFormErrors({});
    setFormSubmitError(null);
    setIsSavingForm(true);

    const normalizedPhone = normalizeAndValidateOptionalPhone(formValues.phone).normalized;
    const payload = {
      name: formValues.name.trim(),
      email: formValues.email.trim(),
      phone: normalizedPhone,
      company: formValues.company.trim() || null,
      notes: formValues.notes.trim() || null,
    };

    try {
      if (formModalMode === 'create') {
        const created = await customersApi.createCustomer(payload);
        setBannerMessage(`Added customer ${created.name} (${created.email}).`);
      } else if (formModalMode === 'edit' && editingCustomer) {
        const updated = await customersApi.updateCustomer(editingCustomer.id, payload);
        setBannerMessage(
          `Updated contact info for ${updated.name}${updated.phone ? ` (${updated.phone})` : ''}. Non-paid invoice phone fields were synced safely without affecting approved snapshots.`
        );
      }
      setFormModalMode(null);
      setEditingCustomer(null);
      await loadCustomers(true);
    } catch (error) {
      if (error instanceof ApiClientError) {
        setFormSubmitError(error.message);
        if (Object.keys(error.fieldErrors).length > 0) {
          setFormErrors({
            name: error.fieldErrors.name,
            email: error.fieldErrors.email,
            phone: error.fieldErrors.phone,
            company: error.fieldErrors.company,
            notes: error.fieldErrors.notes,
          });
        }
      } else {
        setFormSubmitError('Unable to save customer contact details right now.');
      }
    } finally {
      setIsSavingForm(false);
    }
  };

  const handleCreateInvoiceForCustomer = (customer: CustomerResponse) => {
    navigate('/invoices/create', {
      state: { preselectedCustomer: customer },
    });
  };

  return (
    <div className="space-y-5">
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-semibold tracking-tight text-slate-900">
                Customer Directory
              </h2>
              <span className="inline-flex items-center rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 text-xs font-medium text-slate-600">
                {customers.length} {customers.length === 1 ? 'Customer' : 'Customers'}
              </span>
            </div>
            <p className="mt-1 text-sm text-slate-600">
              Automatically synced from your invoices. Keep emails and international E.164 phone numbers (<code className="font-mono text-xs">+...</code>) up to date for Email and WhatsApp payment-link delivery.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2.5">
            <button
              type="button"
              onClick={() => void loadCustomers(true)}
              disabled={isLoading || isRefreshing}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 disabled:opacity-60"
            >
              <RefreshIcon className={`h-4 w-4 ${isRefreshing ? 'animate-spin' : ''}`} />
              <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
            </button>

            <button
              type="button"
              onClick={openCreateModal}
              className="inline-flex items-center gap-2 rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2"
            >
              <UsersIcon className="h-4 w-4" />
              <span>Add Customer</span>
            </button>
          </div>
        </div>

        <div className="mt-5 flex flex-col gap-3 border-t border-slate-100 pt-5 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              aria-pressed={!onlyMissingPhone}
              onClick={() => setOnlyMissingPhone(false)}
              className={[
                'rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors',
                !onlyMissingPhone
                  ? 'bg-slate-900 text-white'
                  : 'border border-slate-200 bg-slate-50 text-slate-600 hover:bg-slate-100',
              ].join(' ')}
            >
              All Customers ({customers.length})
            </button>
            <button
              type="button"
              aria-pressed={onlyMissingPhone}
              onClick={() => setOnlyMissingPhone(true)}
              className={[
                'rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors',
                onlyMissingPhone
                  ? 'bg-amber-600 text-white'
                  : 'border border-amber-200 bg-amber-50/70 text-amber-900 hover:bg-amber-100',
              ].join(' ')}
            >
              Missing Phone ({missingPhoneCount})
            </button>
          </div>

          <div className="w-full sm:w-80">
            <label htmlFor="customer-search" className="sr-only">
              Search customers
            </label>
            <input
              id="customer-search"
              type="search"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search name, email, phone, company..."
              className="block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-primary-600 focus:outline-none focus:ring-2 focus:ring-primary-100"
            />
          </div>
        </div>
      </section>

      {bannerMessage ? (
        <AlertBanner tone="success" title="Customer updated" message={bannerMessage} />
      ) : null}

      {errorMessage ? (
        <AlertBanner tone="error" title="Could not load customers" message={errorMessage} />
      ) : null}

      {isLoading ? (
        <div className="rounded-xl border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
          Syncing and loading customer records...
        </div>
      ) : filteredCustomers.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
          <h3 className="text-base font-semibold text-slate-900">
            {customers.length === 0 ? 'No customers yet' : 'No matching customers found'}
          </h3>
          <p className="mx-auto mt-1 max-w-md text-sm text-slate-600">
            {customers.length === 0
              ? 'Customers appear automatically as soon as you create an invoice, or you can add one manually now.'
              : 'Try adjusting your search query or switching back to All Customers.'}
          </p>
          <div className="mt-5 flex justify-center gap-3">
            {customers.length === 0 ? (
              <button
                type="button"
                onClick={openCreateModal}
                className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
              >
                Add First Customer
              </button>
            ) : (
              <button
                type="button"
                onClick={() => {
                  setSearchQuery('');
                  setOnlyMissingPhone(false);
                }}
                className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Clear Filters
              </button>
            )}
          </div>
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
          {/* Desktop table */}
          <div className="hidden overflow-x-auto md:block">
            <table className="min-w-full divide-y divide-slate-200 text-left text-sm">
              <thead className="bg-slate-50/90 text-xs font-semibold uppercase tracking-wider text-slate-500">
                <tr>
                  <th scope="col" className="px-5 py-3.5">Customer</th>
                  <th scope="col" className="px-5 py-3.5">Email</th>
                  <th scope="col" className="px-5 py-3.5">WhatsApp Phone</th>
                  <th scope="col" className="px-5 py-3.5">Invoices</th>
                  <th scope="col" className="px-5 py-3.5 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white">
                {filteredCustomers.map((customer) => (
                  <tr key={customer.id} className="hover:bg-slate-50/70">
                    <td className="px-5 py-4">
                      <p className="font-semibold text-slate-900">{customer.name}</p>
                      {customer.company ? (
                        <p className="text-xs text-slate-500">{customer.company}</p>
                      ) : null}
                    </td>
                    <td className="px-5 py-4 text-slate-700">{customer.email}</td>
                    <td className="px-5 py-4">
                      {customer.phone ? (
                        <span className="font-mono text-xs font-medium text-slate-800">
                          {customer.phone}
                        </span>
                      ) : (
                        <span className="inline-flex items-center rounded-md border border-amber-200 bg-amber-50 px-2 py-0.5 text-[11px] font-medium text-amber-800">
                          Missing phone
                        </span>
                      )}
                    </td>
                    <td className="px-5 py-4">
                      <span className="font-semibold tabular-nums text-slate-900">
                        {customer.invoiceCount}
                      </span>
                    </td>
                    <td className="whitespace-nowrap px-5 py-4 text-right space-x-2">
                      <button
                        type="button"
                        onClick={() => openEditModal(customer)}
                        className="rounded-lg border border-slate-300 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
                      >
                        {customer.phone ? 'Edit Contact' : 'Add Phone'}
                      </button>
                      <button
                        type="button"
                        disabled={isLoadingDetail}
                        onClick={() => void handleOpenCustomerDetail(customer.id)}
                        className="rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-100"
                      >
                        Invoices
                      </button>
                      <button
                        type="button"
                        onClick={() => handleCreateInvoiceForCustomer(customer)}
                        className="rounded-lg bg-primary-50 px-2.5 py-1.5 text-xs font-semibold text-primary-700 hover:bg-primary-100"
                      >
                        New Invoice
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Mobile card list */}
          <ul className="divide-y divide-slate-100 md:hidden">
            {filteredCustomers.map((customer) => (
              <li key={customer.id} className="p-4 space-y-2.5">
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <p className="text-sm font-semibold text-slate-900">{customer.name}</p>
                    <p className="text-xs text-slate-600">{customer.email}</p>
                    {customer.company ? (
                      <p className="text-xs text-slate-500">{customer.company}</p>
                    ) : null}
                  </div>
                  <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-700">
                    {customer.invoiceCount} inv
                  </span>
                </div>

                <div>
                  {customer.phone ? (
                    <p className="font-mono text-xs text-slate-700">{customer.phone}</p>
                  ) : (
                    <span className="inline-flex items-center rounded-md border border-amber-200 bg-amber-50 px-2 py-0.5 text-[11px] font-medium text-amber-800">
                      Missing WhatsApp phone
                    </span>
                  )}
                </div>

                <div className="flex flex-wrap gap-2 pt-1">
                  <button
                    type="button"
                    onClick={() => openEditModal(customer)}
                    className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
                  >
                    {customer.phone ? 'Edit Contact' : 'Add Phone'}
                  </button>
                  <button
                    type="button"
                    disabled={isLoadingDetail}
                    onClick={() => void handleOpenCustomerDetail(customer.id)}
                    className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-100"
                  >
                    Invoices
                  </button>
                  <button
                    type="button"
                    onClick={() => handleCreateInvoiceForCustomer(customer)}
                    className="rounded-lg bg-primary-50 px-3 py-1.5 text-xs font-semibold text-primary-700 hover:bg-primary-100"
                  >
                    New Invoice
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Create / Edit Customer Modal */}
      {formModalMode ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="customer-modal-title"
        >
          <div className="fixed inset-0 bg-slate-900/45 backdrop-blur-[1px]" onClick={closeFormModal} />
          <div className="relative z-10 w-full max-w-lg rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h3 id="customer-modal-title" className="text-lg font-semibold text-slate-900">
                  {formModalMode === 'create' ? 'Add Customer' : 'Update Customer Contact'}
                </h3>
                <p className="mt-1 text-xs text-slate-600">
                  Stored privately in your seller workspace. Phone must use international E.164 format starting with `+` (e.g. `+14155552671`).
                </p>
              </div>
              <button
                type="button"
                onClick={closeFormModal}
                aria-label="Close customer modal"
                className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
              >
                <CloseIcon />
              </button>
            </div>

            {formSubmitError ? (
              <AlertBanner
                tone="error"
                title="Could not save customer"
                message={formSubmitError}
                className="mt-4"
              />
            ) : null}

            <form onSubmit={handleSubmitForm} noValidate className="mt-5 space-y-4">
              <FormField
                id="customer-form-name"
                label="Customer name"
                type="text"
                name="name"
                required
                disabled={isSavingForm}
                value={formValues.name}
                onChange={(e) => setFormValues((prev) => ({ ...prev, name: e.target.value }))}
                error={formErrors.name}
              />

              <FormField
                id="customer-form-email"
                label="Email address"
                type="email"
                name="email"
                required
                disabled={isSavingForm}
                value={formValues.email}
                onChange={(e) => setFormValues((prev) => ({ ...prev, email: e.target.value }))}
                error={formErrors.email}
              />

              <FormField
                id="customer-form-phone"
                label="WhatsApp / Mobile Phone (Optional)"
                type="tel"
                name="phone"
                placeholder="+14155552671"
                disabled={isSavingForm}
                value={formValues.phone}
                onChange={(e) => setFormValues((prev) => ({ ...prev, phone: e.target.value }))}
                error={formErrors.phone}
                hint="Include country code starting with + (spaces or hyphens allowed)."
              />

              <FormField
                id="customer-form-company"
                label="Company (Optional)"
                type="text"
                name="company"
                disabled={isSavingForm}
                value={formValues.company}
                onChange={(e) => setFormValues((prev) => ({ ...prev, company: e.target.value }))}
                error={formErrors.company}
              />

              <div className="space-y-1.5">
                <label htmlFor="customer-form-notes" className="block text-sm font-medium text-slate-800">
                  Notes (Optional)
                </label>
                <textarea
                  id="customer-form-notes"
                  name="notes"
                  rows={2}
                  maxLength={1000}
                  disabled={isSavingForm}
                  value={formValues.notes}
                  onChange={(e) => setFormValues((prev) => ({ ...prev, notes: e.target.value }))}
                  className="block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm text-slate-900 focus:border-primary-600 focus:outline-none focus:ring-2 focus:ring-primary-100"
                />
                {formErrors.notes ? (
                  <p role="alert" className="text-xs font-medium text-red-600">
                    {formErrors.notes}
                  </p>
                ) : null}
              </div>

              <div className="flex justify-end gap-2.5 border-t border-slate-100 pt-4">
                <button
                  type="button"
                  disabled={isSavingForm}
                  onClick={closeFormModal}
                  className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isSavingForm}
                  className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700 disabled:opacity-60"
                >
                  {isSavingForm ? 'Saving...' : 'Save Customer'}
                </button>
              </div>
            </form>
          </div>
        </div>
      ) : null}

      {/* Customer Detail / Invoices Modal */}
      {detailModal ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="customer-detail-title"
        >
          <div
            className="fixed inset-0 bg-slate-900/45 backdrop-blur-[1px]"
            onClick={() => setDetailModal(null)}
          />
          <div className="relative z-10 w-full max-w-2xl rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-start justify-between gap-3 border-b border-slate-100 pb-4">
              <div>
                <h3 id="customer-detail-title" className="text-lg font-semibold text-slate-900">
                  {detailModal.customer.name}
                </h3>
                <p className="text-xs text-slate-600">
                  {detailModal.customer.email}
                  {detailModal.customer.phone ? ` · ${detailModal.customer.phone}` : ''}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setDetailModal(null)}
                aria-label="Close customer invoices modal"
                className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
              >
                <CloseIcon />
              </button>
            </div>

            <div className="mt-4 max-h-80 overflow-y-auto">
              {detailModal.invoices.length === 0 ? (
                <p className="py-6 text-center text-sm text-slate-500">
                  No invoices linked to this customer yet.
                </p>
              ) : (
                <ul className="divide-y divide-slate-100">
                  {detailModal.invoices.map((inv) => (
                    <li key={inv.id} className="flex items-center justify-between gap-3 py-3 text-sm">
                      <div>
                        <Link
                          to={`/invoices/${inv.id}`}
                          className="font-semibold text-primary-600 hover:underline"
                        >
                          {inv.invoiceNumber}
                        </Link>
                        <p className="text-xs text-slate-500">
                          Due {formatDisplayDate(inv.dueDate)} · {inv.description}
                        </p>
                      </div>
                      <div className="flex items-center gap-3 shrink-0">
                        <span className="font-semibold tabular-nums text-slate-900">
                          {formatInvoiceMoney(inv.amount, inv.currency)}
                        </span>
                        <InvoiceStatusBadge status={inv.status} />
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="mt-5 flex justify-end gap-2.5 border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={() => handleCreateInvoiceForCustomer(detailModal.customer)}
                className="rounded-lg bg-primary-600 px-4 py-2 text-xs font-semibold text-white hover:bg-primary-700"
              >
                Create Invoice For Customer
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
