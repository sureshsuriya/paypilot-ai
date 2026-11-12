import { useEffect, useMemo, useState } from 'react';
import { customersApi } from '@/api';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { FormField } from '@/components/ui/FormField';
import { CheckIcon, CloseIcon, CopyIcon, MessageSquareIcon } from '@/components/ui/Icons';
import {
  ApiClientError,
  type CachedShareLink,
  type InvoiceResponse,
} from '@/types';
import { buildWhatsAppShareUrl, formatWhatsAppInvoiceMessage } from '@/utils/whatsapp';

export interface ShareWhatsAppModalProps {
  open: boolean;
  invoice: InvoiceResponse;
  shareLink: CachedShareLink | null;
  onClose: () => void;
  onPhoneSaved?: () => Promise<void> | void;
}

export function ShareWhatsAppModal({
  open,
  invoice,
  shareLink,
  onClose,
  onPhoneSaved,
}: ShareWhatsAppModalProps) {
  const [phoneInput, setPhoneInput] = useState(invoice.clientPhone ?? '');
  const [phoneError, setPhoneError] = useState<string | undefined>(undefined);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [isSavingPhone, setIsSavingPhone] = useState(false);
  const [copiedMessage, setCopiedMessage] = useState(false);

  useEffect(() => {
    if (!open) {
      return;
    }
    setPhoneInput(invoice.clientPhone ?? '');
    setPhoneError(undefined);
    setSaveMessage(null);
    setSaveError(null);
    setCopiedMessage(false);
  }, [open, invoice.clientPhone]);

  const formattedMessage = useMemo(() => {
    if (!shareLink) {
      return '';
    }
    return formatWhatsAppInvoiceMessage(invoice, shareLink.paymentUrl);
  }, [invoice, shareLink]);

  const whatsAppLinkResult = useMemo(() => {
    if (!shareLink) {
      return { error: 'Generate an active customer payment link first.' };
    }
    return buildWhatsAppShareUrl(phoneInput, formattedMessage);
  }, [phoneInput, formattedMessage, shareLink]);

  if (!open) {
    return null;
  }

  const handleSavePhoneToCustomer = async () => {
    if (!invoice.customerId || isSavingPhone) {
      return;
    }
    const check = buildWhatsAppShareUrl(phoneInput, 'check');
    if (check.error || !check.normalizedPhone) {
      setPhoneError(check.error);
      return;
    }

    setPhoneError(undefined);
    setSaveError(null);
    setSaveMessage(null);
    setIsSavingPhone(true);

    try {
      const existing = await customersApi.getCustomer(invoice.customerId);
      await customersApi.updateCustomer(invoice.customerId, {
        name: existing.customer.name,
        email: existing.customer.email,
        phone: check.normalizedPhone,
        company: existing.customer.company,
        notes: existing.customer.notes,
      });
      setPhoneInput(check.normalizedPhone);
      setSaveMessage(
        `Saved ${check.normalizedPhone} to customer directory without affecting invoice approval.`
      );
      await onPhoneSaved?.();
    } catch (error) {
      setSaveError(
        error instanceof ApiClientError
          ? error.message
          : 'Unable to save customer phone number right now.'
      );
    } finally {
      setIsSavingPhone(false);
    }
  };

  const handleCopyMessage = async () => {
    if (!formattedMessage) {
      return;
    }
    try {
      await navigator.clipboard.writeText(formattedMessage);
      setCopiedMessage(true);
      window.setTimeout(() => setCopiedMessage(false), 2500);
    } catch {
      setCopiedMessage(false);
    }
  };

  const handleOpenWhatsApp = () => {
    if (!shareLink) {
      return;
    }
    if (whatsAppLinkResult.error || !whatsAppLinkResult.url) {
      setPhoneError(whatsAppLinkResult.error);
      return;
    }
    setPhoneError(undefined);
    window.open(whatsAppLinkResult.url, '_blank', 'noopener,noreferrer');
  };

  const canSaveCustomerPhone =
    Boolean(invoice.customerId) &&
    phoneInput.trim().length > 0 &&
    phoneInput.trim() !== (invoice.clientPhone ?? '');

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="whatsapp-modal-title"
    >
      <div
        className="fixed inset-0 bg-slate-900/45 backdrop-blur-[1px]"
        onClick={() => {
          if (!isSavingPhone) {
            onClose();
          }
        }}
      />

      <div className="relative z-10 flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl">
        <div className="flex items-start justify-between gap-3 border-b border-slate-200 px-6 py-4">
          <div className="flex items-center gap-2.5">
            <div className="rounded-lg bg-emerald-50 p-2 text-emerald-700">
              <MessageSquareIcon className="h-5 w-5" />
            </div>
            <div>
              <h3 id="whatsapp-modal-title" className="text-base font-semibold text-slate-900">
                Share Payment Link via WhatsApp
              </h3>
              <p className="text-xs text-slate-500">
                Invoice {invoice.invoiceNumber} · Pre-filled draft for manual WhatsApp delivery
              </p>
            </div>
          </div>
          <button
            type="button"
            disabled={isSavingPhone}
            onClick={onClose}
            aria-label="Close WhatsApp modal"
            className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-50"
          >
            <CloseIcon />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-5 space-y-5">
          {!shareLink ? (
            <AlertBanner
              tone="warning"
              title="No active payment link in this session"
              message="Generate or regenerate an active customer payment link first before preparing a WhatsApp message."
            />
          ) : null}

          <AlertBanner
            tone="info"
            title="Manual confirmation inside WhatsApp"
            message="Opening WhatsApp launches a pre-filled draft in WhatsApp Web or the app so you can review and press Send yourself. PayPilot AI never auto-sends messages or marks this invoice as sent automatically."
          />

          {saveMessage ? (
            <AlertBanner tone="success" title="Phone updated" message={saveMessage} />
          ) : null}
          {saveError ? (
            <AlertBanner tone="error" title="Could not save phone" message={saveError} />
          ) : null}

          <div className="space-y-2">
            <FormField
              id="whatsapp-recipient-phone"
              label="Customer WhatsApp phone number (E.164 with country code)"
              type="tel"
              name="whatsappPhone"
              placeholder="+14155552671"
              disabled={isSavingPhone || !shareLink}
              value={phoneInput}
              onChange={(e) => {
                setPhoneInput(e.target.value);
                if (phoneError) {
                  setPhoneError(undefined);
                }
              }}
              error={phoneError}
              hint="Include + and country code (e.g. +14155552671 for US, +919876543210 for India)."
            />

            {canSaveCustomerPhone ? (
              <div className="flex items-center justify-end">
                <button
                  type="button"
                  disabled={isSavingPhone}
                  onClick={() => void handleSavePhoneToCustomer()}
                  className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-60"
                >
                  {isSavingPhone ? 'Saving phone...' : 'Save Phone to Customer Record'}
                </button>
              </div>
            ) : null}
          </div>

          {shareLink ? (
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <label
                  htmlFor="whatsapp-message-preview"
                  className="text-xs font-semibold uppercase tracking-wider text-slate-500"
                >
                  Formatted WhatsApp Message Preview
                </label>
                {whatsAppLinkResult.normalizedPhone ? (
                  <span className="font-mono text-xs text-emerald-700">
                    Target: {whatsAppLinkResult.normalizedPhone}
                  </span>
                ) : null}
              </div>

              <textarea
                id="whatsapp-message-preview"
                readOnly
                rows={11}
                value={formattedMessage}
                className="block w-full rounded-xl border border-slate-200 bg-slate-50 p-3.5 font-mono text-xs leading-relaxed text-slate-800 focus:border-primary-600 focus:outline-none"
              />
            </div>
          ) : null}

          <div className="flex flex-wrap items-center justify-between gap-2.5 border-t border-slate-100 pt-4">
            <button
              type="button"
              disabled={!shareLink}
              onClick={() => void handleCopyMessage()}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-4 py-2 text-xs font-semibold text-slate-800 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {copiedMessage ? (
                <>
                  <CheckIcon className="h-3.5 w-3.5 text-emerald-600" />
                  <span>Message Copied!</span>
                </>
              ) : (
                <>
                  <CopyIcon className="h-3.5 w-3.5" />
                  <span>Copy Message</span>
                </>
              )}
            </button>

            <div className="flex items-center gap-2.5">
              <button
                type="button"
                disabled={isSavingPhone}
                onClick={onClose}
                className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
              >
                Close
              </button>

              <button
                type="button"
                disabled={!shareLink || isSavingPhone}
                onClick={handleOpenWhatsApp}
                className="inline-flex items-center gap-2 rounded-lg bg-emerald-600 px-5 py-2 text-sm font-semibold text-white shadow-sm hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <MessageSquareIcon className="h-4 w-4" />
                <span>Open WhatsApp to Send</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

