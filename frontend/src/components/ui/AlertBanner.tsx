export type AlertTone = 'error' | 'warning' | 'info' | 'success';

export interface AlertBannerProps {
  tone?: AlertTone;
  title?: string;
  message: string;
  details?: string[];
  className?: string;
}

const TONE_CLASSES: Record<AlertTone, string> = {
  error: 'border-red-200 bg-red-50/80 text-red-900',
  warning: 'border-amber-200 bg-amber-50/80 text-amber-900',
  info: 'border-primary-100 bg-primary-50/70 text-slate-900',
  success: 'border-emerald-200 bg-emerald-50/80 text-emerald-900',
};

export function AlertBanner({
  tone = 'info',
  title,
  message,
  details,
  className = '',
}: AlertBannerProps) {
  return (
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      className={`rounded-lg border px-3.5 py-3 text-sm ${TONE_CLASSES[tone]} ${className}`}
    >
      {title ? <p className="font-semibold">{title}</p> : null}
      <p className={title ? 'mt-0.5 text-xs leading-relaxed opacity-90' : 'leading-relaxed'}>
        {message}
      </p>
      {details && details.length > 0 ? (
        <ul className="mt-1.5 list-disc space-y-0.5 pl-4 text-xs opacity-90">
          {details.map((detail) => (
            <li key={detail}>{detail}</li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

