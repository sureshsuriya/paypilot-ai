import { useEffect, type ReactNode } from 'react';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  tone?: 'primary' | 'warning' | 'danger';
  isBusy?: boolean;
  details?: Array<{ label: string; value: ReactNode }>;
  warningNote?: string;
  onConfirm: () => void;
  onCancel: () => void;
}

const CONFIRM_BUTTON_STYLES: Record<NonNullable<ConfirmDialogProps['tone']>, string> = {
  primary: 'bg-primary-600 text-white hover:bg-primary-700 focus:ring-primary-500',
  warning: 'bg-amber-600 text-white hover:bg-amber-700 focus:ring-amber-500',
  danger: 'bg-red-600 text-white hover:bg-red-700 focus:ring-red-500',
};

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel = 'Cancel',
  tone = 'primary',
  isBusy = false,
  details,
  warningNote,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  useEffect(() => {
    if (!open || isBusy) {
      return undefined;
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onCancel();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [open, isBusy, onCancel]);

  if (!open) {
    return null;
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
    >
      <div
        className="fixed inset-0 bg-slate-900/50 backdrop-blur-[1px]"
        onClick={() => {
          if (!isBusy) {
            onCancel();
          }
        }}
      />

      <div className="relative z-10 w-full max-w-lg rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
        <h3 id="confirm-dialog-title" className="text-lg font-semibold text-slate-900">
          {title}
        </h3>
        <p className="mt-2 text-sm leading-relaxed text-slate-600">{description}</p>

        {details && details.length > 0 ? (
          <dl className="mt-4 space-y-2 rounded-xl border border-slate-200 bg-slate-50/80 p-3.5 text-xs">
            {details.map((item) => (
              <div key={item.label} className="flex items-start justify-between gap-4">
                <dt className="font-medium text-slate-500">{item.label}</dt>
                <dd className="text-right font-semibold text-slate-900 break-all">
                  {item.value}
                </dd>
              </div>
            ))}
          </dl>
        ) : null}

        {warningNote ? (
          <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50/80 px-3.5 py-2.5 text-xs leading-relaxed text-amber-900">
            {warningNote}
          </div>
        ) : null}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          <button
            type="button"
            disabled={isBusy}
            onClick={onCancel}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-slate-400 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            disabled={isBusy}
            onClick={onConfirm}
            className={`inline-flex items-center justify-center rounded-lg px-4 py-2 text-sm font-semibold shadow-sm transition-colors focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60 ${CONFIRM_BUTTON_STYLES[tone]}`}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

