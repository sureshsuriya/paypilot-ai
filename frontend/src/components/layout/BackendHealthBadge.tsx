import { RefreshIcon } from '@/components/ui/Icons';
import type { BackendHealthState } from '@/hooks/useBackendHealth';

export interface BackendHealthBadgeProps {
  health: BackendHealthState;
}

const STATUS_CONFIG = {
  checking: {
    label: 'Checking API',
    dotClass: 'bg-slate-400 animate-pulse',
    pillClass: 'border-slate-200 bg-slate-50 text-slate-700',
  },
  online: {
    label: 'Backend Online',
    dotClass: 'bg-emerald-500',
    pillClass: 'border-emerald-200 bg-emerald-50/70 text-emerald-800',
  },
  offline: {
    label: 'Backend Offline',
    dotClass: 'bg-red-500',
    pillClass: 'border-red-200 bg-red-50/80 text-red-800',
  },
} as const;

export function BackendHealthBadge({ health }: BackendHealthBadgeProps) {
  const cfg = STATUS_CONFIG[health.status];
  const titleText = health.lastCheckedAt
    ? `${cfg.label} (last checked ${health.lastCheckedAt})`
    : cfg.label;

  return (
    <div
      title={titleText}
      className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium ${cfg.pillClass}`}
    >
      <span className={`h-2 w-2 rounded-full ${cfg.dotClass}`} aria-hidden="true" />
      <span>{cfg.label}</span>
      {health.status === 'offline' ? (
        <button
          type="button"
          onClick={() => void health.recheck()}
          title="Retry backend health check"
          aria-label="Retry backend health check"
          className="inline-flex items-center rounded p-0.5 text-red-700 hover:bg-red-100 focus:outline-none focus:ring-2 focus:ring-red-400"
        >
          <RefreshIcon />
        </button>
      ) : null}
    </div>
  );
}

