import { useEffect, useState } from 'react';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { CheckIcon, CopyIcon, LinkIcon, MailIcon, MessageSquareIcon } from '@/components/ui/Icons';
import type {
  CachedShareLink,
  InvoiceResponse,
  PaymentLinkSummaryResponse,
} from '@/types';
import { formatDisplayDate } from '@/utils/money';

export interface PaymentLinkManagerPanelProps {
  invoice: InvoiceResponse;
  links: PaymentLinkSummaryResponse[];
  cachedShareLink: CachedShareLink | null;
  hasPendingOrCapturedPayment: boolean;
  isBusy: boolean;
  onGenerateOrRotate: (expiresInDays: number) => Promise<void>;
  onRevokeActive: (reason: string) => Promise<void>;
  onShareByEmail?: (shareLink: CachedShareLink) => void;
  onShareByWhatsApp?: (shareLink: CachedShareLink) => void;
}

const EXPIRY_OPTIONS = [
  { label: '7 days', value: 7 },
  { label: '14 days (Recommended)', value: 14 },
  { label: '30 days', value: 30 },
  { label: '60 days', value: 60 },
];

export function PaymentLinkManagerPanel({
  invoice,
  links,
  cachedShareLink,
  hasPendingOrCapturedPayment,
  isBusy,
  onGenerateOrRotate,
  onRevokeActive,
  onShareByEmail,
  onShareByWhatsApp,
}: PaymentLinkManagerPanelProps) {
  const [selectedTtlDays, setSelectedTtlDays] = useState<number>(14);
  const [confirmMode, setConfirmMode] = useState<'rotate' | 'revoke' | null>(null);
  const [revokeReason, setRevokeReason] = useState('Revoked by seller');
  const [copiedUrl, setCopiedUrl] = useState(false);

  const activeLink = links.find((link) => link.active) ?? null;
  const activeShareLink =
    activeLink && cachedShareLink && cachedShareLink.paymentLinkId === activeLink.id
      ? cachedShareLink
      : null;

  useEffect(() => {
    setCopiedUrl(false);
  }, [activeShareLink?.paymentUrl]);

  const handleCopyUrl = async () => {
    if (!activeShareLink) {
      return;
    }
    try {
      await navigator.clipboard.writeText(activeShareLink.paymentUrl);
      setCopiedUrl(true);
      window.setTimeout(() => setCopiedUrl(false), 2500);
    } catch {
      setCopiedUrl(false);
    }
  };

  const renderLinkStatusBadge = (link: PaymentLinkSummaryResponse) => {
    if (link.revoked) {
      return (
        <span className="inline-flex items-center rounded-full bg-slate-100 px-2.5 py-0.5 text-[11px] font-semibold text-slate-700">
          REVOKED
        </span>
      );
    }
    if (link.expired) {
      return (
        <span className="inline-flex items-center rounded-full bg-amber-100 px-2.5 py-0.5 text-[11px] font-semibold text-amber-800">
          EXPIRED
        </span>
      );
    }
    return (
      <span className="inline-flex items-center rounded-full bg-emerald-100 px-2.5 py-0.5 text-[11px] font-semibold text-emerald-800">
        ACTIVE
      </span>
    );
  };

  if (invoice.status === 'DRAFT') {
    return (
      <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex items-start gap-3">
          <div className="rounded-xl bg-slate-100 p-2.5 text-slate-600">
            <LinkIcon className="h-5 w-5" />
          </div>
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              Customer Payment Link
            </h3>
            <p className="mt-1 text-sm text-slate-600">
              Approve this invoice first to generate a secure, single-active customer payment link (`/pay/:token`). Only one active link is allowed per invoice at any time.
            </p>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm space-y-5">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
        <div className="max-w-2xl space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-base font-semibold text-slate-900">
              Customer Payment Link &amp; Delivery
            </h3>
            {activeLink ? renderLinkStatusBadge(activeLink) : null}
          </div>
          <p className="text-sm text-slate-600">
            Share an encrypted public link so{' '}
            <strong className="font-medium text-slate-900">{invoice.clientName}</strong> can review
            this approved invoice and pay safely via PayPal Sandbox without seller credentials.
          </p>
        </div>

        {invoice.status === 'APPROVED' && !hasPendingOrCapturedPayment ? (
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="payment-link-ttl" className="sr-only">
              Link expiration
            </label>
            <select
              id="payment-link-ttl"
              value={selectedTtlDays}
              disabled={isBusy}
              onChange={(e) => setSelectedTtlDays(Number(e.target.value))}
              className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-xs font-medium text-slate-700 focus:border-primary-600 focus:outline-none focus:ring-2 focus:ring-primary-100 disabled:opacity-60"
            >
              {EXPIRY_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  Expires in {opt.label}
                </option>
              ))}
            </select>

            {activeLink ? (
              <>
                <button
                  type="button"
                  disabled={isBusy}
                  onClick={() => setConfirmMode('rotate')}
                  className="rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {isBusy ? 'Working...' : 'Regenerate Link'}
                </button>
                <button
                  type="button"
                  disabled={isBusy}
                  onClick={() => setConfirmMode('revoke')}
                  className="rounded-lg border border-red-200 bg-white px-3.5 py-2 text-xs font-semibold text-red-700 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  Revoke Link
                </button>
              </>
            ) : (
              <button
                type="button"
                disabled={isBusy}
                onClick={() => void onGenerateOrRotate(selectedTtlDays)}
                className="inline-flex items-center gap-2 rounded-lg bg-slate-900 px-4 py-2 text-xs font-semibold text-white shadow-sm transition-colors hover:bg-slate-800 focus:outline-none focus:ring-2 focus:ring-slate-900 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
              >
                <LinkIcon className="h-3.5 w-3.5" />
                <span>{isBusy ? 'Generating...' : 'Generate Payment Link'}</span>
              </button>
            )}
          </div>
        ) : null}
      </div>

      {activeLink ? (
        <div className="rounded-xl border border-slate-200 bg-slate-50/75 p-4 space-y-4">
          <div className="grid gap-3 text-xs sm:grid-cols-4">
            <div>
              <span className="block font-semibold uppercase tracking-wider text-slate-500">
                Token Prefix
              </span>
              <span className="mt-1 block font-mono font-semibold text-slate-800">
                {activeLink.tokenPrefix}
              </span>
            </div>
            <div>
              <span className="block font-semibold uppercase tracking-wider text-slate-500">
                Expires At
              </span>
              <span className="mt-1 block font-medium text-slate-800">
                {formatDisplayDate(activeLink.expiresAt)}
              </span>
            </div>
            <div>
              <span className="block font-semibold uppercase tracking-wider text-slate-500">
                Customer Views
              </span>
              <span className="mt-1 block font-medium text-slate-800">
                {activeLink.accessCount} {activeLink.accessCount === 1 ? 'visit' : 'visits'}
              </span>
            </div>
            <div>
              <span className="block font-semibold uppercase tracking-wider text-slate-500">
                Last Opened
              </span>
              <span className="mt-1 block font-medium text-slate-800">
                {activeLink.lastAccessedAt ? formatDisplayDate(activeLink.lastAccessedAt) : 'Not opened yet'}
              </span>
            </div>
          </div>

          {activeShareLink ? (
            <div className="space-y-3 border-t border-slate-200/80 pt-4">
              <label
                htmlFor="generated-payment-url"
                className="block text-xs font-semibold uppercase tracking-wider text-slate-600"
              >
                Shareable Customer Payment URL
              </label>
              <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
                <input
                  id="generated-payment-url"
                  type="text"
                  readOnly
                  value={activeShareLink.paymentUrl}
                  aria-label="Customer payment URL"
                  onFocus={(e) => e.currentTarget.select()}
                  className="block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 font-mono text-xs text-slate-800 focus:border-primary-600 focus:outline-none"
                />
                <div className="flex shrink-0 flex-wrap items-center gap-2">
                  <button
                    type="button"
                    onClick={() => void handleCopyUrl()}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-800 transition-colors hover:bg-slate-100"
                  >
                    {copiedUrl ? (
                      <>
                        <CheckIcon className="h-3.5 w-3.5 text-emerald-600" />
                        <span>Copied!</span>
                      </>
                    ) : (
                      <>
                        <CopyIcon className="h-3.5 w-3.5" />
                        <span>Copy Link</span>
                      </>
                    )}
                  </button>

                  {onShareByEmail ? (
                    <button
                      type="button"
                      onClick={() => onShareByEmail(activeShareLink)}
                      className="inline-flex items-center gap-1.5 rounded-lg bg-primary-600 px-3.5 py-2 text-xs font-semibold text-white transition-colors hover:bg-primary-700"
                    >
                      <MailIcon className="h-3.5 w-3.5" />
                      <span>Send Email</span>
                    </button>
                  ) : null}

                  {onShareByWhatsApp ? (
                    <button
                      type="button"
                      onClick={() => onShareByWhatsApp(activeShareLink)}
                      className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-300 bg-emerald-50 px-3.5 py-2 text-xs font-semibold text-emerald-900 transition-colors hover:bg-emerald-100"
                    >
                      <MessageSquareIcon className="h-3.5 w-3.5" />
                      <span>WhatsApp</span>
                    </button>
                  ) : null}
                </div>
              </div>
              <p className="text-[11px] text-slate-500">
                Security guarantee: Only one active link exists for this invoice, and only its SHA-256 hash is stored in the database.
              </p>
            </div>
          ) : (
            <div className="rounded-lg border border-amber-200 bg-amber-50/80 p-3 text-xs text-amber-900">
              An active link (<span className="font-mono font-semibold">{activeLink.tokenPrefix}</span>) is registered on the server, but its raw token was generated in a previous browser session. For security, PayPilot stores only a SHA-256 hash on the server — click <strong>Regenerate Link</strong> above to issue a fresh copyable URL.
            </div>
          )}
        </div>
      ) : (
        <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50/40 p-4 text-xs text-slate-600">
          {invoice.status === 'PAID'
            ? 'This invoice is already PAID. New payment links cannot be issued for settled invoices.'
            : hasPendingOrCapturedPayment
              ? 'Payment link generation is paused while a PayPal capture is pending confirmation.'
              : 'No active payment link exists yet. Choose an expiration window and click "Generate Payment Link" to create a unique link for your customer.'}
        </div>
      )}

      {links.length > 0 ? (
        <div className="space-y-2 border-t border-slate-100 pt-4">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
            Payment Link Audit History ({links.length})
          </h4>
          <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white">
            {links.map((link) => (
              <li
                key={link.id}
                className="flex flex-col justify-between gap-2 px-4 py-3 text-xs sm:flex-row sm:items-center"
              >
                <div className="flex flex-wrap items-center gap-2">
                  {renderLinkStatusBadge(link)}
                  <span className="font-mono font-medium text-slate-800">
                    {link.tokenPrefix}
                  </span>
                  <span className="text-slate-500">
                    Created {formatDisplayDate(link.createdAt)} · Expires {formatDisplayDate(link.expiresAt)}
                  </span>
                  {link.revocationReason ? (
                    <span className="text-slate-500">({link.revocationReason})</span>
                  ) : null}
                </div>
                <span className="shrink-0 font-medium text-slate-600">
                  {link.accessCount} {link.accessCount === 1 ? 'view' : 'views'}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <ConfirmDialog
        open={confirmMode === 'rotate'}
        title="Regenerate Customer Payment Link?"
        description="Generating a new payment link automatically revokes the current active link so at most one active link exists per invoice."
        confirmLabel={isBusy ? 'Regenerating...' : 'Revoke Old & Generate New'}
        tone="warning"
        isBusy={isBusy}
        warningNote="Anyone holding the previous payment link URL will see a Revoked screen."
        onConfirm={async () => {
          setConfirmMode(null);
          await onGenerateOrRotate(selectedTtlDays);
        }}
        onCancel={() => setConfirmMode(null)}
      />

      <ConfirmDialog
        open={confirmMode === 'revoke'}
        title="Revoke Active Payment Link?"
        description="Customers opening the revoked URL will be blocked from viewing invoice terms or creating PayPal orders."
        confirmLabel={isBusy ? 'Revoking...' : 'Confirm Revoke Link'}
        tone="danger"
        isBusy={isBusy}
        warningNote={revokeReason ? `Reason recorded: ${revokeReason}` : undefined}
        onConfirm={async () => {
          const reason = revokeReason.trim() || 'Revoked by seller';
          setConfirmMode(null);
          setRevokeReason('Revoked by seller');
          await onRevokeActive(reason);
        }}
        onCancel={() => setConfirmMode(null)}
      />
    </section>
  );
}

