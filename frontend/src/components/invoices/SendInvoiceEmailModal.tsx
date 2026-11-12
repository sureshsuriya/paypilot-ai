import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { emailApi } from '@/api';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { FormField } from '@/components/ui/FormField';
import { CloseIcon, MailIcon, RefreshIcon } from '@/components/ui/Icons';
import {
  ApiClientError,
  type CachedShareLink,
  type InvoiceEmailPreviewResponse,
  type InvoiceEmailSendResponse,
  type InvoiceResponse,
} from '@/types';
import { validateEmailValue } from '@/utils/validation';

export interface SendInvoiceEmailModalProps {
  open: boolean;
  invoice: InvoiceResponse;
  shareLink: CachedShareLink | null;
  onClose: () => void;
  onEmailSent: (response: InvoiceEmailSendResponse) => Promise<void> | void;
}

export function SendInvoiceEmailModal({
  open,
  invoice,
  shareLink,
  onClose,
  onEmailSent,
}: SendInvoiceEmailModalProps) {
  const [recipientEmail, setRecipientEmail] = useState(invoice.clientEmail);
  const [customMessage, setCustomMessage] = useState('');
  const [emailError, setEmailError] = useState<string | undefined>(undefined);
  const [previewMode, setPreviewMode] = useState<'html' | 'text'>('html');

  const [preview, setPreview] = useState<InvoiceEmailPreviewResponse | null>(null);
  const [isLoadingPreview, setIsLoadingPreview] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  const [isSending, setIsSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  const loadPreview = useCallback(
    async (targetEmail: string, note: string) => {
      if (!shareLink) {
        return;
      }
      setIsLoadingPreview(true);
      setPreviewError(null);
      try {
        const res = await emailApi.previewInvoiceEmail(invoice.id, {
          rawToken: shareLink.rawToken,
          recipientEmail: targetEmail.trim() || null,
          customMessage: note.trim() || null,
        });
        setPreview(res);
      } catch (error) {
        setPreviewError(
          error instanceof ApiClientError
            ? error.message
            : 'Unable to render email preview right now.'
        );
      } finally {
        setIsLoadingPreview(false);
      }
    },
    [invoice.id, shareLink]
  );

  useEffect(() => {
    if (!open) {
      return;
    }
    setRecipientEmail(invoice.clientEmail);
    setCustomMessage('');
    setEmailError(undefined);
    setSendError(null);
    setPreviewMode('html');
    void loadPreview(invoice.clientEmail, '');
  }, [open, invoice.clientEmail, loadPreview]);

  if (!open) {
    return null;
  }

  const handleSendEmail = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!shareLink || isSending) {
      return;
    }

    const validationIssue = validateEmailValue(recipientEmail);
    if (validationIssue) {
      setEmailError(validationIssue);
      return;
    }

    setEmailError(undefined);
    setSendError(null);
    setIsSending(true);

    try {
      const result = await emailApi.sendInvoiceEmail(invoice.id, {
        rawToken: shareLink.rawToken,
        recipientEmail: recipientEmail.trim(),
        customMessage: customMessage.trim() || null,
      });
      await onEmailSent(result);
    } catch (error) {
      setSendError(
        error instanceof ApiClientError
          ? error.message
          : 'Unable to dispatch invoice email right now.'
      );
    } finally {
      setIsSending(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="email-modal-title"
    >
      <div
        className="fixed inset-0 bg-slate-900/45 backdrop-blur-[1px]"
        onClick={() => {
          if (!isSending) {
            onClose();
          }
        }}
      />

      <div className="relative z-10 flex max-h-[90vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl">
        <div className="flex items-start justify-between gap-3 border-b border-slate-200 px-6 py-4">
          <div className="flex items-center gap-2.5">
            <div className="rounded-lg bg-primary-50 p-2 text-primary-600">
              <MailIcon className="h-5 w-5" />
            </div>
            <div>
              <h3 id="email-modal-title" className="text-base font-semibold text-slate-900">
                Email Invoice Payment Link
              </h3>
              <p className="text-xs text-slate-500">
                Invoice {invoice.invoiceNumber} · Verified backend link ({shareLink?.tokenPrefix ?? 'Unavailable'})
              </p>
            </div>
          </div>
          <button
            type="button"
            disabled={isSending}
            onClick={onClose}
            aria-label="Close email modal"
            className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-50"
          >
            <CloseIcon />
          </button>
        </div>

        <form onSubmit={handleSendEmail} noValidate className="flex-1 overflow-y-auto px-6 py-5 space-y-5">
          {!shareLink ? (
            <AlertBanner
              tone="warning"
              title="No active payment link in this session"
              message="Generate or regenerate an active payment link first before sending an invoice email."
            />
          ) : null}

          {preview && !preview.mailConfigured ? (
            <AlertBanner
              tone="warning"
              title="SMTP email transport is currently disabled"
              message="Preview works normally below, but real SMTP sending requires MAIL_ENABLED=true, MAIL_HOST, MAIL_USERNAME, and MAIL_PASSWORD configured in backend/.env."
            />
          ) : null}

          {previewError ? (
            <AlertBanner tone="error" title="Preview unavailable" message={previewError} />
          ) : null}

          {sendError ? (
            <AlertBanner tone="error" title="Email delivery failed" message={sendError} />
          ) : null}

          <div className="grid gap-4 sm:grid-cols-2">
            <FormField
              id="email-recipient"
              label="Recipient email"
              type="email"
              name="recipientEmail"
              required
              disabled={isSending || !shareLink}
              value={recipientEmail}
              onChange={(e) => {
                setRecipientEmail(e.target.value);
                if (emailError) {
                  setEmailError(undefined);
                }
              }}
              error={emailError}
            />

            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label htmlFor="email-custom-message" className="block text-sm font-medium text-slate-800">
                  Personal note (Optional)
                </label>
                <span className="text-xs text-slate-400">{customMessage.length} / 500</span>
              </div>
              <textarea
                id="email-custom-message"
                rows={2}
                maxLength={500}
                disabled={isSending || !shareLink}
                value={customMessage}
                onChange={(e) => setCustomMessage(e.target.value)}
                placeholder="Thank you for your business — click the Pay button to review and complete PayPal Sandbox payment."
                className="block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-primary-600 focus:outline-none focus:ring-2 focus:ring-primary-100"
              />
            </div>
          </div>

          {shareLink ? (
            <div className="space-y-2.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Email Preview
                  </span>
                  <div className="inline-flex rounded-lg border border-slate-200 bg-slate-100 p-0.5 text-xs">
                    <button
                      type="button"
                      onClick={() => setPreviewMode('html')}
                      className={`rounded-md px-2.5 py-1 font-medium ${
                        previewMode === 'html' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-600'
                      }`}
                    >
                      HTML View
                    </button>
                    <button
                      type="button"
                      onClick={() => setPreviewMode('text')}
                      className={`rounded-md px-2.5 py-1 font-medium ${
                        previewMode === 'text' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-600'
                      }`}
                    >
                      Plain Text
                    </button>
                  </div>
                </div>

                <button
                  type="button"
                  disabled={isLoadingPreview || isSending}
                  onClick={() => {
                    const issue = validateEmailValue(recipientEmail);
                    if (issue) {
                      setEmailError(issue);
                      return;
                    }
                    void loadPreview(recipientEmail, customMessage);
                  }}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
                >
                  <RefreshIcon className={`h-3.5 w-3.5 ${isLoadingPreview ? 'animate-spin' : ''}`} />
                  <span>{isLoadingPreview ? 'Updating...' : 'Update Preview'}</span>
                </button>
              </div>

              {preview ? (
                <div className="overflow-hidden rounded-xl border border-slate-200 bg-slate-50">
                  <div className="border-b border-slate-200 bg-white px-4 py-2 text-xs text-slate-600">
                    <span className="font-semibold text-slate-800">Subject:</span> {preview.subject}
                  </div>
                  {previewMode === 'html' ? (
                    <iframe
                      title="Invoice email HTML preview"
                      sandbox=""
                      srcDoc={preview.htmlBody}
                      className="h-80 w-full bg-slate-100"
                    />
                  ) : (
                    <pre className="h-80 overflow-y-auto whitespace-pre-wrap p-4 font-mono text-xs text-slate-800">
                      {preview.textBody}
                    </pre>
                  )}
                </div>
              ) : isLoadingPreview ? (
                <div className="rounded-xl border border-slate-200 bg-slate-50 p-10 text-center text-xs text-slate-500">
                  Generating server-verified email preview...
                </div>
              ) : null}
            </div>
          ) : null}

          <div className="flex flex-wrap items-center justify-end gap-2.5 border-t border-slate-100 pt-4">
            <button
              type="button"
              disabled={isSending}
              onClick={onClose}
              className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={!shareLink || isSending || isLoadingPreview}
              className="inline-flex items-center gap-2 rounded-lg bg-primary-600 px-5 py-2 text-sm font-semibold text-white shadow-sm hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <MailIcon className="h-4 w-4" />
              <span>{isSending ? 'Sending Email...' : 'Send Invoice Email'}</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

