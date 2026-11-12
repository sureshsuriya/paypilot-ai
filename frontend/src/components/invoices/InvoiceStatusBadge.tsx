import type { InvoiceStatus } from '@/types';

export interface InvoiceStatusBadgeProps {
  status: InvoiceStatus;
}

const STATUS_STYLE: Record<
  InvoiceStatus,
  { label: string; badgeClass: string; dotClass: string }
> = {
  DRAFT: {
    label: 'DRAFT',
    badgeClass: 'border-slate-200 bg-slate-100 text-slate-700',
    dotClass: 'bg-slate-400',
  },
  APPROVED: {
    label: 'APPROVED',
    badgeClass: 'border-primary-100 bg-primary-50 text-primary-700',
    dotClass: 'bg-primary-600',
  },
  SENT: {
    label: 'SENT',
    badgeClass: 'border-purple-200 bg-purple-50 text-purple-800',
    dotClass: 'bg-purple-500',
  },
  PAID: {
    label: 'PAID',
    badgeClass: 'border-emerald-200 bg-emerald-50 text-emerald-800',
    dotClass: 'bg-emerald-500',
  },
  OVERDUE: {
    label: 'OVERDUE',
    badgeClass: 'border-amber-200 bg-amber-50 text-amber-800',
    dotClass: 'bg-amber-500',
  },
  CANCELLED: {
    label: 'CANCELLED',
    badgeClass: 'border-red-200 bg-red-50 text-red-700',
    dotClass: 'bg-red-500',
  },
};

export function InvoiceStatusBadge({ status }: InvoiceStatusBadgeProps) {
  const cfg = STATUS_STYLE[status] ?? STATUS_STYLE.DRAFT;

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-semibold tracking-tight ${cfg.badgeClass}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${cfg.dotClass}`} aria-hidden="true" />
      <span>{cfg.label}</span>
    </span>
  );
}

