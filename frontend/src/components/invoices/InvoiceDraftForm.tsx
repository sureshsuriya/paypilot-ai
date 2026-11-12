import type { FormEvent } from 'react';
import { FormField } from '@/components/ui/FormField';
import type { CustomerResponse } from '@/types';
import {
  getTodayLocalIsoDate,
  type InvoiceDraftFormErrors,
  type InvoiceDraftFormValues,
} from '@/utils/validation';

export interface InvoiceDraftFormProps {
  values: InvoiceDraftFormValues;
  errors: InvoiceDraftFormErrors;
  isAiGenerated: boolean;
  aiMissingFields?: string[];
  aiValidationErrors?: Record<string, string>;
  hasConfirmedReview: boolean;
  isSaving: boolean;
  customers?: CustomerResponse[];
  selectedCustomerId?: number | '';
  onSelectCustomer?: (customerId: number | '') => void;
  title?: string;
  subtitle?: string;
  modeBadgeLabel?: string;
  submitLabel?: string;
  onChangeField: (field: keyof InvoiceDraftFormValues, value: string) => void;
  onToggleConfirmReview: (confirmed: boolean) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onReset?: () => void;
  onCancel?: () => void;
}

const COMMON_CURRENCIES = ['USD', 'EUR', 'GBP', 'CAD', 'AUD', 'INR'];

export function InvoiceDraftForm({
  values,
  errors,
  isAiGenerated,
  aiMissingFields = [],
  aiValidationErrors = {},
  hasConfirmedReview,
  isSaving,
  customers = [],
  selectedCustomerId = '',
  onSelectCustomer,
  title,
  subtitle,
  modeBadgeLabel,
  submitLabel,
  onChangeField,
  onToggleConfirmReview,
  onSubmit,
  onReset,
  onCancel,
}: InvoiceDraftFormProps) {
  const missingSet = new Set(aiMissingFields);
  const todayIso = getTodayLocalIsoDate();

  const fieldHint = (field: keyof InvoiceDraftFormValues, defaultHint?: string) => {
    if (aiValidationErrors[field]) {
      return `AI suggestion notice: ${aiValidationErrors[field]}`;
    }
    if (missingSet.has(field)) {
      return 'Not provided in prompt — enter manually before saving.';
    }
    return defaultHint;
  };

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-4">
        <div>
          <h3 className="text-base font-semibold text-slate-900">
            {title ??
              (isAiGenerated
                ? 'Review & Confirm Extracted Invoice Draft'
                : 'Manual Invoice Details')}
          </h3>
          <p className="mt-0.5 text-xs text-slate-600">
            {subtitle ??
              (isAiGenerated
                ? 'Edit any AI-suggested value and fill missing details before saving as a DRAFT invoice.'
                : 'Enter verified client and amount details to create a DRAFT invoice.')}
          </p>
        </div>

        {isAiGenerated ? (
          <span
            className={[
              'inline-flex items-center rounded-full px-2.5 py-1 text-xs font-medium',
              hasConfirmedReview
                ? 'bg-emerald-100 text-emerald-800'
                : 'bg-purple-100 text-purple-800',
            ].join(' ')}
          >
            {hasConfirmedReview ? 'User-Confirmed Draft' : 'AI-Suggested · Unconfirmed'}
          </span>
        ) : (
          <span className="inline-flex items-center rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-700">
            {modeBadgeLabel ?? 'Manual Entry'}
          </span>
        )}
      </div>

      {onSelectCustomer && customers.length > 0 ? (
        <div className="rounded-xl border border-slate-200 bg-slate-50/70 p-3.5">
          <label
            htmlFor="invoice-saved-customer"
            className="block text-xs font-semibold uppercase tracking-wider text-slate-600"
          >
            Quick-Fill From Saved Customer (Optional)
          </label>
          <select
            id="invoice-saved-customer"
            value={selectedCustomerId}
            disabled={isSaving}
            onChange={(e) => {
              const raw = e.target.value;
              onSelectCustomer(raw ? Number(raw) : '');
            }}
            className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-primary-600 focus:outline-none focus:ring-2 focus:ring-primary-100 disabled:cursor-not-allowed disabled:bg-slate-100"
          >
            <option value="">-- Enter new client details or select a saved customer --</option>
            {customers.map((customer) => (
              <option key={customer.id} value={customer.id}>
                {customer.name} ({customer.email})
                {customer.phone ? ` · ${customer.phone}` : ''}
              </option>
            ))}
          </select>
        </div>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <FormField
          id="invoice-client-name"
          label="Client name"
          type="text"
          name="clientName"
          maxLength={255}
          placeholder="Alex Mercer"
          value={values.clientName}
          onChange={(e) => onChangeField('clientName', e.target.value)}
          error={errors.clientName}
          hint={fieldHint('clientName')}
          disabled={isSaving}
          required
        />

        <FormField
          id="invoice-client-email"
          label="Client email"
          type="email"
          name="clientEmail"
          maxLength={255}
          placeholder="alex@clientcompany.com"
          value={values.clientEmail}
          onChange={(e) => onChangeField('clientEmail', e.target.value)}
          error={errors.clientEmail}
          hint={fieldHint('clientEmail')}
          disabled={isSaving}
          required
        />

        <FormField
          id="invoice-client-phone"
          label="Client WhatsApp / Phone (Optional)"
          type="tel"
          name="clientPhone"
          maxLength={32}
          placeholder="+14155552671"
          value={values.clientPhone}
          onChange={(e) => onChangeField('clientPhone', e.target.value)}
          error={errors.clientPhone}
          hint="International E.164 format with country code (for WhatsApp link sharing)."
          disabled={isSaving}
        />
      </div>

      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <label htmlFor="invoice-description" className="block text-sm font-medium text-slate-800">
            Description<span className="ml-0.5 text-red-600" aria-hidden="true">*</span>
          </label>
          <span className="text-xs text-slate-400">{values.description.length} / 5000</span>
        </div>
        <textarea
          id="invoice-description"
          name="description"
          rows={3}
          maxLength={5000}
          required
          disabled={isSaving}
          aria-invalid={Boolean(errors.description)}
          placeholder="Website design and development milestone"
          value={values.description}
          onChange={(e) => onChangeField('description', e.target.value)}
          className={[
            'block w-full rounded-lg border bg-white px-3.5 py-2.5 text-sm text-slate-900',
            'placeholder:text-slate-400 transition-colors duration-150',
            'focus:outline-none focus:ring-2 focus:ring-offset-0 disabled:cursor-not-allowed disabled:bg-slate-50',
            errors.description
              ? 'border-red-400 focus:border-red-500 focus:ring-red-100'
              : 'border-slate-300 hover:border-slate-400 focus:border-primary-600 focus:ring-primary-100',
          ].join(' ')}
        />
        {errors.description ? (
          <p role="alert" className="text-xs font-medium text-red-600">
            {errors.description}
          </p>
        ) : fieldHint('description') ? (
          <p className="text-xs text-slate-500">{fieldHint('description')}</p>
        ) : null}
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <FormField
          id="invoice-amount"
          label="Amount"
          type="text"
          inputMode="decimal"
          name="amount"
          placeholder="750.00"
          value={values.amount}
          onChange={(e) => onChangeField('amount', e.target.value)}
          error={errors.amount}
          hint={fieldHint('amount', 'Decimal up to 2 fractional places (min 0.01).')}
          disabled={isSaving}
          required
        />

        <div>
          <FormField
            id="invoice-currency"
            label="Currency (ISO-4217)"
            type="text"
            name="currency"
            list="paypilot-currency-options"
            maxLength={3}
            placeholder="USD"
            value={values.currency}
            onChange={(e) => onChangeField('currency', e.target.value.toUpperCase())}
            error={errors.currency}
            hint={fieldHint('currency', '3-letter code, e.g. USD.')}
            disabled={isSaving}
            required
          />
          <datalist id="paypilot-currency-options">
            {COMMON_CURRENCIES.map((code) => (
              <option key={code} value={code} />
            ))}
          </datalist>
        </div>

        <FormField
          id="invoice-due-date"
          label="Due date"
          type="date"
          name="dueDate"
          min={todayIso}
          value={values.dueDate}
          onChange={(e) => onChangeField('dueDate', e.target.value)}
          error={errors.dueDate}
          hint={fieldHint('dueDate', 'Today or a future date.')}
          disabled={isSaving}
          required
        />
      </div>

      {isAiGenerated ? (
        <div className="rounded-xl border border-slate-200 bg-slate-50/80 p-4">
          <label className="flex cursor-pointer items-start gap-3">
            <input
              type="checkbox"
              checked={hasConfirmedReview}
              disabled={isSaving}
              onChange={(e) => onToggleConfirmReview(e.target.checked)}
              className="mt-0.5 h-4 w-4 rounded border-slate-300 text-primary-600 focus:ring-primary-500"
            />
            <span className="text-xs leading-relaxed text-slate-700">
              <strong className="font-semibold text-slate-900">Human confirmation required:</strong>{' '}
              I have reviewed the AI-extracted fields above, completed any missing details, and
              verified the client email, monetary amount, currency, and due date.
            </span>
          </label>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center justify-end gap-3 border-t border-slate-100 pt-4">
        {onCancel ? (
          <button
            type="button"
            disabled={isSaving}
            onClick={onCancel}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
          >
            Cancel
          </button>
        ) : null}

        {onReset ? (
          <button
            type="button"
            disabled={isSaving}
            onClick={onReset}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
          >
            Clear Form
          </button>
        ) : null}

        <button
          type="submit"
          disabled={isSaving || (isAiGenerated && !hasConfirmedReview)}
          className="inline-flex items-center justify-center rounded-lg bg-primary-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {isSaving
            ? 'Saving...'
            : submitLabel ??
              (isAiGenerated ? 'Confirm & Save Draft Invoice' : 'Save Draft Invoice')}
        </button>
      </div>
    </form>
  );
}

