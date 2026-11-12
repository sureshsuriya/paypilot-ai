import { useEffect, useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { aiApi, customersApi, invoicesApi } from '@/api';
import { AiDraftSummaryPanel } from '@/components/ai/AiDraftSummaryPanel';
import { InvoiceDraftForm } from '@/components/invoices/InvoiceDraftForm';
import { AlertBanner } from '@/components/ui/AlertBanner';
import {
  ApiClientError,
  type CreateInvoiceRequest,
  type CustomerResponse,
  type ExtractedInvoiceDraftResponse,
} from '@/types';
import {
  normalizeAndValidateOptionalPhone,
  validateExtractionPrompt,
  validateInvoiceDraftForm,
  type InvoiceDraftFormErrors,
  type InvoiceDraftFormValues,
} from '@/utils/validation';

type CreationMode = 'ai' | 'manual';

interface CreateLocationState {
  preselectedCustomer?: CustomerResponse;
}

const EXAMPLE_PROMPT =
  'Create a $750 invoice for Alex for website development. Payment is due in seven days.';

const EMPTY_FORM_VALUES: InvoiceDraftFormValues = {
  clientName: '',
  clientEmail: '',
  clientPhone: '',
  description: '',
  amount: '',
  currency: 'USD',
  dueDate: '',
};

function mapExtractedDraftToForm(
  draft: ExtractedInvoiceDraftResponse['draft'],
  existingPhone = ''
): InvoiceDraftFormValues {
  return {
    clientName: draft.clientName ?? '',
    clientEmail: draft.clientEmail ?? '',
    clientPhone: existingPhone,
    description: draft.description ?? '',
    amount:
      draft.amount !== null && Number.isFinite(draft.amount)
        ? draft.amount.toFixed(2)
        : '',
    currency: draft.currency ? draft.currency.toUpperCase() : '',
    dueDate: draft.dueDate ?? '',
  };
}

export function CreateInvoicePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const preselected = (location.state as CreateLocationState | null)?.preselectedCustomer ?? null;

  const [mode, setMode] = useState<CreationMode>(preselected ? 'manual' : 'ai');
  const [customers, setCustomers] = useState<CustomerResponse[]>([]);
  const [selectedCustomerId, setSelectedCustomerId] = useState<number | ''>(
    preselected ? preselected.id : ''
  );

  const [prompt, setPrompt] = useState('');
  const [promptError, setPromptError] = useState<string | null>(null);
  const [extractError, setExtractError] = useState<string | null>(null);
  const [isExtracting, setIsExtracting] = useState(false);

  const [extraction, setExtraction] = useState<ExtractedInvoiceDraftResponse | null>(null);
  const [formValues, setFormValues] = useState<InvoiceDraftFormValues>(() =>
    preselected
      ? {
          ...EMPTY_FORM_VALUES,
          clientName: preselected.name,
          clientEmail: preselected.email,
          clientPhone: preselected.phone ?? '',
        }
      : EMPTY_FORM_VALUES
  );
  const [formErrors, setFormErrors] = useState<InvoiceDraftFormErrors>({});
  const [hasConfirmedReview, setHasConfirmedReview] = useState(false);

  const [saveError, setSaveError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    let active = true;
    customersApi
      .listCustomers()
      .then((list) => {
        if (active) {
          setCustomers(list);
        }
      })
      .catch(() => {
        // Non-blocking quick-fill customer list
      });
    return () => {
      active = false;
    };
  }, []);

  const switchMode = (nextMode: CreationMode) => {
    if (nextMode === mode || isExtracting || isSaving) {
      return;
    }
    setMode(nextMode);
    setFormErrors({});
    setSaveError(null);
    setExtractError(null);
    if (nextMode === 'manual') {
      setExtraction(null);
      setHasConfirmedReview(false);
      setFormValues((prev) => ({
        ...prev,
        currency: prev.currency || 'USD',
      }));
    }
  };

  const handleSelectSavedCustomer = (customerId: number | '') => {
    setSelectedCustomerId(customerId);
    if (customerId === '') {
      return;
    }
    const chosen = customers.find((c) => c.id === customerId);
    if (!chosen) {
      return;
    }
    setFormValues((prev) => ({
      ...prev,
      clientName: chosen.name,
      clientEmail: chosen.email,
      clientPhone: chosen.phone ?? '',
    }));
    setFormErrors((prev) => ({
      ...prev,
      clientName: undefined,
      clientEmail: undefined,
      clientPhone: undefined,
    }));
  };

  const handleExtractDraft = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isExtracting || isSaving) {
      return;
    }

    const validationIssue = validateExtractionPrompt(prompt);
    if (validationIssue) {
      setPromptError(validationIssue);
      return;
    }

    setPromptError(null);
    setExtractError(null);
    setSaveError(null);
    setFormErrors({});
    setIsExtracting(true);

    try {
      const response = await aiApi.extractInvoiceDraft({ prompt: prompt.trim() });
      setExtraction(response);
      setFormValues(mapExtractedDraftToForm(response.draft, formValues.clientPhone));
      setHasConfirmedReview(false);
    } catch (error) {
      setExtractError(
        error instanceof ApiClientError
          ? error.message
          : 'AI extraction service could not process the prompt right now.'
      );
    } finally {
      setIsExtracting(false);
    }
  };

  const handleChangeField = (field: keyof InvoiceDraftFormValues, value: string) => {
    setFormValues((prev) => ({ ...prev, [field]: value }));
    setFormErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev));
    if (field === 'clientEmail' && selectedCustomerId !== '') {
      const selected = customers.find((c) => c.id === selectedCustomerId);
      if (selected && selected.email.toLowerCase() !== value.trim().toLowerCase()) {
        setSelectedCustomerId('');
      }
    }
    if (saveError) {
      setSaveError(null);
    }
  };

  const handleResetForm = () => {
    setFormValues(EMPTY_FORM_VALUES);
    setSelectedCustomerId('');
    setFormErrors({});
    setSaveError(null);
    setHasConfirmedReview(false);
    if (mode === 'ai') {
      setExtraction(null);
    }
  };

  const handleSaveInvoice = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isSaving || isExtracting) {
      return;
    }

    const isAiDraft = mode === 'ai' && extraction !== null;
    if (isAiDraft && !hasConfirmedReview) {
      setSaveError('Please confirm that you have reviewed all AI-extracted fields before saving.');
      return;
    }

    const validationErrors = validateInvoiceDraftForm(formValues);
    if (Object.keys(validationErrors).length > 0) {
      setFormErrors(validationErrors);
      setSaveError('Please correct the highlighted invoice fields before saving.');
      return;
    }

    setFormErrors({});
    setSaveError(null);
    setIsSaving(true);

    const normalizedPhone = normalizeAndValidateOptionalPhone(formValues.clientPhone).normalized;

    const payload: CreateInvoiceRequest = {
      clientName: formValues.clientName.trim(),
      clientEmail: formValues.clientEmail.trim(),
      clientPhone: normalizedPhone,
      customerId: selectedCustomerId !== '' ? selectedCustomerId : undefined,
      description: formValues.description.trim(),
      amount: Number(formValues.amount.trim()),
      currency: formValues.currency.trim().toUpperCase(),
      dueDate: formValues.dueDate.trim(),
      ...(isAiDraft ? { extractionLogId: extraction.extractionLogId } : {}),
    };

    try {
      const created = await invoicesApi.createInvoice(payload);
      navigate(`/invoices/${created.id}`, {
        replace: true,
        state: {
          justCreated: true,
          invoiceNumber: created.invoiceNumber,
        },
      });
    } catch (error) {
      if (error instanceof ApiClientError) {
        setSaveError(error.message);
        if (Object.keys(error.fieldErrors).length > 0) {
          setFormErrors({
            clientName: error.fieldErrors.clientName,
            clientEmail: error.fieldErrors.clientEmail,
            clientPhone: error.fieldErrors.clientPhone,
            description: error.fieldErrors.description,
            amount: error.fieldErrors.amount,
            currency: error.fieldErrors.currency,
            dueDate: error.fieldErrors.dueDate,
          });
        }
      } else {
        setSaveError('Unable to save the draft invoice right now. Please try again.');
      }
    } finally {
      setIsSaving(false);
    }
  };

  const showDraftForm = mode === 'manual' || extraction !== null;

  return (
    <div className="space-y-6">
      <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
          <div>
            <span className="inline-flex items-center rounded-md border border-primary-100 bg-primary-50 px-2.5 py-0.5 text-xs font-medium text-primary-700">
              Draft Creation · Human Confirmation Required
            </span>
            <h2 className="mt-2 text-xl font-semibold tracking-tight text-slate-900 sm:text-2xl">
              Create Draft Invoice
            </h2>
            <p className="mt-1 text-sm text-slate-600">
              Extract structured draft fields via our Gemini AI proxy or enter invoice details directly. Saving creates a DRAFT invoice only — no payment or approval occurs automatically.
            </p>
          </div>

          <div
            role="tablist"
            aria-label="Invoice creation method"
            className="inline-flex rounded-xl border border-slate-200 bg-slate-100 p-1"
          >
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'ai'}
              disabled={isExtracting || isSaving}
              onClick={() => switchMode('ai')}
              className={[
                'rounded-lg px-3.5 py-2 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-primary-500',
                mode === 'ai'
                  ? 'bg-white text-slate-900 shadow-sm'
                  : 'text-slate-600 hover:text-slate-900',
              ].join(' ')}
            >
              AI-Assisted Draft
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'manual'}
              disabled={isExtracting || isSaving}
              onClick={() => switchMode('manual')}
              className={[
                'rounded-lg px-3.5 py-2 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-primary-500',
                mode === 'manual'
                  ? 'bg-white text-slate-900 shadow-sm'
                  : 'text-slate-600 hover:text-slate-900',
              ].join(' ')}
            >
              Manual Entry
            </button>
          </div>
        </div>
      </section>

      {mode === 'ai' ? (
        <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
          <form onSubmit={handleExtractDraft} noValidate className="space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <label htmlFor="ai-invoice-prompt" className="text-sm font-semibold text-slate-900">
                  Describe the invoice in plain language
                </label>
                <span className="ai-label">Backend AI Proxy</span>
              </div>

              <button
                type="button"
                disabled={isExtracting || isSaving}
                onClick={() => {
                  setPrompt(EXAMPLE_PROMPT);
                  setPromptError(null);
                  setExtractError(null);
                }}
                className="text-xs font-semibold text-primary-600 hover:text-primary-700 disabled:opacity-50"
              >
                Insert example prompt
              </button>
            </div>

            <textarea
              id="ai-invoice-prompt"
              name="prompt"
              rows={4}
              maxLength={2000}
              disabled={isExtracting || isSaving}
              value={prompt}
              onChange={(e) => {
                setPrompt(e.target.value);
                if (promptError) {
                  setPromptError(null);
                }
                if (extractError) {
                  setExtractError(null);
                }
              }}
              aria-invalid={Boolean(promptError)}
              placeholder={EXAMPLE_PROMPT}
              className={[
                'block w-full rounded-xl border bg-white px-4 py-3 text-sm text-slate-900',
                'placeholder:text-slate-400 transition-colors focus:outline-none focus:ring-2',
                promptError
                  ? 'border-red-400 focus:border-red-500 focus:ring-red-100'
                  : 'border-slate-300 hover:border-slate-400 focus:border-primary-600 focus:ring-primary-100',
              ].join(' ')}
            />

            <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-slate-500">
              <span>
                {promptError ? (
                  <span role="alert" className="font-medium text-red-600">
                    {promptError}
                  </span>
                ) : (
                  'Include client name, email, work description, amount, currency, and due date.'
                )}
              </span>
              <span>{prompt.length} / 2000</span>
            </div>

            {extractError ? (
              <AlertBanner
                tone="error"
                title="AI draft extraction unavailable"
                message={extractError}
              />
            ) : null}

            <div className="flex flex-wrap items-center justify-between gap-3 pt-1">
              <button
                type="button"
                disabled={isExtracting || isSaving}
                onClick={() => switchMode('manual')}
                className="text-xs font-medium text-slate-600 hover:text-slate-900"
              >
                Or enter fields manually without AI
              </button>

              <button
                type="submit"
                disabled={isExtracting || isSaving}
                className="inline-flex items-center justify-center rounded-lg bg-slate-900 px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-slate-800 focus:outline-none focus:ring-2 focus:ring-slate-900 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {isExtracting ? 'Extracting Draft...' : 'Extract Invoice Draft'}
              </button>
            </div>
          </form>
        </section>
      ) : null}

      {mode === 'ai' && extraction ? <AiDraftSummaryPanel extraction={extraction} /> : null}

      {showDraftForm ? (
        <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
          {saveError ? (
            <AlertBanner
              tone="error"
              title="Could not save invoice"
              message={saveError}
              className="mb-5"
            />
          ) : null}

          <InvoiceDraftForm
            values={formValues}
            errors={formErrors}
            isAiGenerated={mode === 'ai' && extraction !== null}
            aiMissingFields={extraction?.missingFields}
            aiValidationErrors={extraction?.validationErrors}
            hasConfirmedReview={hasConfirmedReview}
            isSaving={isSaving}
            customers={customers}
            selectedCustomerId={selectedCustomerId}
            onSelectCustomer={handleSelectSavedCustomer}
            onChangeField={handleChangeField}
            onToggleConfirmReview={setHasConfirmedReview}
            onSubmit={handleSaveInvoice}
            onReset={handleResetForm}
          />
        </section>
      ) : null}
    </div>
  );
}
