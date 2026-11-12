import type { PaymentStatus } from '@/types';

export interface PaymentStatusBadgeProps {
  status: PaymentStatus;
}

const PAYMENT_STATUS_STYLE: Record<
  PaymentStatus,
  { label: string; badgeClass: string; dotClass: string }
> = {
  CREATED: {
    label: 'CREATED',
    badgeClass: 'border-slate-200 bg-slate-100 text-slate-700',
    dotClass: 'bg-slate-400',
  },
  APPROVED: {
    label: 'APPROVED',
    badgeClass: 'border-primary-100 bg-primary-50 text-primary-700',
    dotClass: 'bg-primary-600',
  },
  PENDING: {
    label: 'PENDING',
    badgeClass: 'border-amber-200 bg-amber-50 text-amber-800',
    dotClass: 'bg-amber-500',
  },
  CAPTURED: {
    label: 'CAPTURED',
    badgeClass: 'border-emerald-200 bg-emerald-50 text-emerald-800',
    dotClass: 'bg-emerald-500',
  },
  FAILED: {
    label: 'FAILED',
    badgeClass: 'border-red-200 bg-red-50 text-red-700',
    dotClass: 'bg-red-500',
  },
  CANCELLED: {
    label: 'CANCELLED',
    badgeClass: 'border-slate-200 bg-slate-100 text-slate-600',
    dotClass: 'bg-slate-400',
  },
};

export function PaymentStatusBadge({ status }: PaymentStatusBadgeProps) {
  const cfg = PAYMENT_STATUS_STYLE[status] ?? PAYMENT_STATUS_STYLE.CREATED;

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-semibold tracking-tight ${cfg.badgeClass}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${cfg.dotClass}`} aria-hidden="true" />
      <span>{cfg.label}</span>
    </span>
  );
}

