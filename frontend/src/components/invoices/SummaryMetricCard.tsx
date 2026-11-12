import type { ReactNode } from 'react';

export interface SummaryMetricCardProps {
  label: string;
  value: ReactNode;
  subtext: string;
  accent?: 'neutral' | 'blue' | 'purple' | 'emerald';
}

const ACCENT_BAR: Record<NonNullable<SummaryMetricCardProps['accent']>, string> = {
  neutral: 'bg-slate-300',
  blue: 'bg-primary-600',
  purple: 'bg-purple-600',
  emerald: 'bg-emerald-500',
};

export function SummaryMetricCard({
  label,
  value,
  subtext,
  accent = 'neutral',
}: SummaryMetricCardProps) {
  return (
    <div className="relative overflow-hidden rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
      <div className={`absolute inset-y-0 left-0 w-1 ${ACCENT_BAR[accent]}`} aria-hidden="true" />
      <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">{label}</p>
      <div className="mt-2 text-2xl font-semibold tracking-tight text-slate-900">{value}</div>
      <p className="mt-1.5 text-xs text-slate-500">{subtext}</p>
    </div>
  );
}

