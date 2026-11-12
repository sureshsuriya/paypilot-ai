export interface SandboxIndicatorProps {
  variant?: 'compact' | 'panel';
}

export function SandboxIndicator({ variant = 'compact' }: SandboxIndicatorProps) {
  if (variant === 'panel') {
    return (
      <div className="rounded-xl border border-sandbox-border/50 bg-sandbox-bg/70 px-3.5 py-3 text-sandbox-text">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-semibold tracking-wide uppercase">
            PayPal Sandbox
          </span>
          <span className="rounded bg-amber-200/80 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-amber-900">
            Test Mode
          </span>
        </div>
        <p className="mt-1 text-xs leading-relaxed text-amber-900/90">
          Payments use PayPal Sandbox only — never real money.
        </p>
      </div>
    );
  }

  return (
    <span
      className="sandbox-badge"
      title="Payments run exclusively in PayPal Sandbox. Real money is never charged."
    >
      PayPal Sandbox Mode — No Real Money
    </span>
  );
}

