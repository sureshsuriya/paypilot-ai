import type { ExtractedInvoiceDraftResponse } from '@/types';

export interface AiDraftSummaryPanelProps {
  extraction: ExtractedInvoiceDraftResponse;
}

const FIELD_DISPLAY_NAMES: Record<string, string> = {
  clientName: 'Client Name',
  clientEmail: 'Client Email',
  description: 'Description',
  amount: 'Amount',
  currency: 'Currency',
  dueDate: 'Due Date',
};

function formatFieldLabel(key: string): string {
  return FIELD_DISPLAY_NAMES[key] ?? key;
}

function formatConfidencePercent(confidence: number | null): string {
  if (confidence === null || !Number.isFinite(confidence)) {
    return 'Not reported';
  }
  return `${Math.round(confidence * 100)}%`;
}

export function AiDraftSummaryPanel({ extraction }: AiDraftSummaryPanelProps) {
  const validationEntries = Object.entries(extraction.validationErrors ?? {});

  return (
    <div className="rounded-xl border border-purple-200 bg-purple-50/50 p-4 sm:p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="ai-label">
            AI-Generated Suggestion · Human Review Required
          </span>
          <span className="inline-flex items-center rounded border border-slate-200 bg-white px-2 py-0.5 text-xs font-medium text-slate-700">
            Model: {extraction.modelUsed}
          </span>
          <span className="inline-flex items-center rounded border border-slate-200 bg-white px-2 py-0.5 text-xs font-medium text-slate-700">
            Confidence: {formatConfidencePercent(extraction.confidence)}
          </span>
        </div>

        <span
          className={[
            'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
            extraction.readyToSave
              ? 'bg-emerald-100 text-emerald-800'
              : 'bg-amber-100 text-amber-800',
          ].join(' ')}
        >
          {extraction.readyToSave ? 'All required fields extracted' : 'Requires manual completion'}
        </span>
      </div>

      <p className="mt-2.5 text-xs font-medium text-slate-700">{extraction.label}</p>

      {extraction.assumptions ? (
        <div className="mt-3 rounded-lg border border-purple-200/80 bg-white px-3.5 py-2.5">
          <p className="text-[11px] font-semibold uppercase tracking-wider text-purple-800">
            AI Assumptions &amp; Notes
          </p>
          <p className="mt-1 text-xs leading-relaxed text-slate-700">{extraction.assumptions}</p>
        </div>
      ) : null}

      {extraction.missingFields.length > 0 ? (
        <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-2.5">
          <p className="text-xs font-semibold text-amber-900">
            Missing fields — please enter these manually before saving:
          </p>
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            {extraction.missingFields.map((field) => (
              <span
                key={field}
                className="inline-flex items-center rounded border border-amber-300 bg-white px-2 py-0.5 text-xs font-medium text-amber-900"
              >
                {formatFieldLabel(field)}
              </span>
            ))}
          </div>
        </div>
      ) : null}

      {validationEntries.length > 0 ? (
        <div className="mt-3 rounded-lg border border-red-200 bg-red-50 px-3.5 py-2.5">
          <p className="text-xs font-semibold text-red-900">
            Backend validation notes on extracted values:
          </p>
          <ul className="mt-1 list-disc space-y-0.5 pl-4 text-xs text-red-800">
            {validationEntries.map(([field, message]) => (
              <li key={field}>
                <span className="font-medium">{formatFieldLabel(field)}:</span> {message}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

