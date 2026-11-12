import type { ReactNode } from 'react';
import { PayPilotMark, ShieldCheckIcon } from '@/components/ui/Icons';

export interface AuthShellProps {
  badgeLabel: string;
  title: string;
  subtitle: string;
  children: ReactNode;
  footer: ReactNode;
}

const HIGHLIGHTS = [
  {
    title: 'AI-Assisted Drafts with Human Review',
    description:
      'Extract structured invoice fields from plain language while retaining complete manual editing control.',
  },
  {
    title: 'Snapshot-Locked Approvals',
    description:
      'Cryptographic SHA-256 invoice snapshots ensure approved totals and client details cannot drift silently.',
  },
  {
    title: 'Authoritative PayPal Sandbox Flow',
    description:
      'Orders and captures execute strictly server-side against PayPal Sandbox — never live funds.',
  },
];

export function AuthShell({
  badgeLabel,
  title,
  subtitle,
  children,
  footer,
}: AuthShellProps) {
  return (
    <div className="min-h-screen bg-slate-50 lg:grid lg:grid-cols-12">
      <aside className="hidden lg:col-span-5 lg:flex lg:flex-col lg:justify-between bg-slate-900 px-10 py-12 text-white">
        <div>
          <div className="flex items-center gap-3">
            <PayPilotMark className="h-9 w-9" />
            <div>
              <span className="text-lg font-semibold tracking-tight text-white">PayPilot AI</span>
              <p className="text-xs text-slate-400">Invoice &amp; Payment Control</p>
            </div>
          </div>

          <div className="mt-14 space-y-4">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-purple-400/30 bg-purple-500/10 px-3 py-1 text-xs font-medium text-purple-200">
              AI-Ready Workspace
            </span>
            <h1 className="text-3xl font-semibold leading-tight tracking-tight text-white">
              Agentic invoicing engineered with strict financial guardrails.
            </h1>
            <p className="text-sm leading-relaxed text-slate-300">
              Draft invoices faster with AI extraction, lock approved terms before checkout, and reconcile PayPal Sandbox captures cleanly.
            </p>
          </div>

          <div className="mt-10 space-y-5 border-t border-slate-800 pt-8">
            {HIGHLIGHTS.map((item) => (
              <div key={item.title} className="flex items-start gap-3">
                <div className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-primary-600/20 text-primary-500">
                  <ShieldCheckIcon className="h-4 w-4 text-primary-100" />
                </div>
                <div>
                  <h2 className="text-sm font-medium text-white">{item.title}</h2>
                  <p className="mt-0.5 text-xs leading-relaxed text-slate-400">
                    {item.description}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="rounded-xl border border-amber-400/25 bg-amber-400/10 px-4 py-3">
          <p className="text-xs font-medium text-amber-200">PayPal Sandbox Environment</p>
          <p className="mt-0.5 text-xs text-amber-100/80">
            All payment actions use PayPal Sandbox test accounts only. Real money is never charged.
          </p>
        </div>
      </aside>

      <main className="flex min-h-screen flex-col items-center justify-center px-4 py-10 sm:px-6 lg:col-span-7 lg:px-12">
        <div className="mb-6 flex items-center gap-2.5 lg:hidden">
          <PayPilotMark className="h-8 w-8" />
          <span className="text-lg font-semibold tracking-tight text-slate-900">PayPilot AI</span>
        </div>

        <div className="w-full max-w-md rounded-2xl border border-slate-200/90 bg-white p-7 shadow-sm sm:p-8">
          <div className="mb-6">
            <span className="inline-flex items-center rounded-md border border-primary-100 bg-primary-50 px-2.5 py-0.5 text-xs font-medium text-primary-700">
              {badgeLabel}
            </span>
            <h2 className="mt-3 text-2xl font-semibold tracking-tight text-slate-900">
              {title}
            </h2>
            <p className="mt-1 text-sm text-slate-600">{subtitle}</p>
          </div>

          {children}

          <div className="mt-6 border-t border-slate-100 pt-5 text-center text-sm text-slate-600">
            {footer}
          </div>
        </div>

        <div className="mt-6 flex flex-col items-center gap-1.5 text-center">
          <span className="sandbox-badge">
            PayPal Sandbox Mode — Test Payments Only
          </span>
          <p className="text-xs text-slate-500">
            No real funds, live cards, or production PayPal accounts are used.
          </p>
        </div>
      </main>
    </div>
  );
}

