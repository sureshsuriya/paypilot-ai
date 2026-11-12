import type { ReactNode } from 'react';
import { SandboxIndicator } from '@/components/layout/SandboxIndicator';
import { PayPilotMark, ShieldCheckIcon } from '@/components/ui/Icons';

export interface CustomerPortalLayoutProps {
  children: ReactNode;
}

/**
 * Isolated public checkout layout for `/pay/:token`.
 * Contains zero Seller Portal navigation, routes, or session controls.
 */
export function CustomerPortalLayout({ children }: CustomerPortalLayoutProps) {
  return (
    <div className="min-h-screen bg-gradient-to-b from-slate-50 via-white to-slate-100/70 text-slate-900">
      <header className="border-b border-slate-200 bg-white/95 backdrop-blur print:border-b-0">
        <div className="mx-auto flex max-w-3xl flex-wrap items-center justify-between gap-3 px-4 py-4 sm:px-6">
          <div className="flex items-center gap-2.5">
            <PayPilotMark className="h-8 w-8 shrink-0" />
            <div>
              <p className="text-sm font-semibold tracking-tight text-slate-900">
                PayPilot AI Checkout
              </p>
              <p className="text-[11px] font-medium text-slate-500">
                Verified Customer Invoice Portal
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2.5">
            <SandboxIndicator variant="compact" />
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-3xl px-4 py-8 sm:px-6 sm:py-10">
        {children}
      </main>

      <footer className="mx-auto max-w-3xl px-4 pb-10 pt-2 text-center print:hidden">
        <div className="inline-flex items-center gap-1.5 rounded-full border border-slate-200 bg-white px-3.5 py-1.5 text-xs text-slate-600 shadow-sm">
          <ShieldCheckIcon className="h-4 w-4 text-emerald-600" />
          <span>
            Authoritative verification by PayPilot AI backend via PayPal Sandbox
          </span>
        </div>
      </footer>
    </div>
  );
}

