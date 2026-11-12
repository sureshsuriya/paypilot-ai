import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { invoicesApi, paymentLinksApi, paymentsApi, emailApi } from '@/api';
import { InvoiceApprovalPanel } from '@/components/invoices/InvoiceApprovalPanel';
import { InvoiceDraftForm } from '@/components/invoices/InvoiceDraftForm';
import { InvoiceStatusBadge } from '@/components/invoices/InvoiceStatusBadge';
import { PaymentLinkManagerPanel } from '@/components/invoices/PaymentLinkManagerPanel';
import { SendInvoiceEmailModal } from '@/components/invoices/SendInvoiceEmailModal';
import { ShareWhatsAppModal } from '@/components/invoices/ShareWhatsAppModal';
import { PaymentFeedbackBanner } from '@/components/payments/PaymentFeedbackBanner';
import { PaymentHistoryTable } from '@/components/payments/PaymentHistoryTable';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { RefreshIcon } from '@/components/ui/Icons';
import { useAuth } from '@/hooks/useAuth';
import {
  ApiClientError,
  type CachedShareLink,
  type InvoiceDeliveryLogResponse,
  type InvoiceEmailSendResponse,
  type InvoiceResponse,
  type PaymentLinkSummaryResponse,
  type PaymentResponse,
  type UpdateInvoiceRequest,
} from '@/types';
import { formatDisplayDate, formatInvoiceMoney } from '@/utils/money';
import {
  buildPayPalRedirectUrls,
  describeCaptureOutcome,
  describePaymentFailure,
  isValidPayPalOrderToken,
  parsePayPalSandboxApprovalUrl,
  type PaymentFeedbackMessage,
} from '@/utils/paypal';
import {
  loadCachedShareLink,
  removeCachedShareLink,
  saveCachedShareLink,
} from '@/utils/shareLinkStorage';
import {
  normalizeAndValidateOptionalPhone,
  validateInvoiceDraftForm,
  type InvoiceDraftFormErrors,
  type InvoiceDraftFormValues,
} from '@/utils/validation';

interface DetailLocationState {
  justCreated?: boolean;
  invoiceNumber?: string;
}

type ActiveModalState =
  | null
  | { type: 'approve' }
  | { type: 'revoke' }
  | { type: 'delete' }
  | { type: 'confirm-edit-approved' }
  | { type: 'cancel-order'; orderId: string };

/**
 * Module-level StrictMode guard preventing duplicate PayPal capture/cancel invocations
 * when React 18 development StrictMode runs initial effects twice.
 */
const handledPayPalCallbackKeys = new Set<string>();

function toFormValues(invoice: InvoiceResponse): InvoiceDraftFormValues {
  return {
    clientName: invoice.clientName,
    clientEmail: invoice.clientEmail,
    clientPhone: invoice.clientPhone ?? '',
    description: invoice.description,
    amount: Number.isFinite(invoice.amount) ? invoice.amount.toFixed(2) : '',
    currency: invoice.currency.toUpperCase(),
    dueDate: invoice.dueDate ?? '',
  };
}

export function InvoiceDetailPage() {
  const { user } = useAuth();
  const { id } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();

  const numericInvoiceId = id && /^\d+$/.test(id) ? Number(id) : NaN;
  const isValidRouteId = Number.isSafeInteger(numericInvoiceId) && numericInvoiceId > 0;

  const locationState = (location.state as DetailLocationState | null) ?? null;

  const [invoice, setInvoice] = useState<InvoiceResponse | null>(null);
  const [payments, setPayments] = useState<PaymentResponse[]>([]);
  const [paymentLinks, setPaymentLinks] = useState<PaymentLinkSummaryResponse[]>([]);
  const [deliveries, setDeliveries] = useState<InvoiceDeliveryLogResponse[]>([]);
  const [cachedShareLink, setCachedShareLink] = useState<CachedShareLink | null>(null);
  const [isLinkBusy, setIsLinkBusy] = useState(false);
  const [isEmailModalOpen, setIsEmailModalOpen] = useState(false);
  const [isWhatsAppModalOpen, setIsWhatsAppModalOpen] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [feedback, setFeedback] = useState<PaymentFeedbackMessage | null>(() =>
    locationState?.justCreated
      ? {
          tone: 'success',
          title: 'Draft invoice created',
          message: `Invoice ${locationState.invoiceNumber ?? ''} was saved as DRAFT. Review all terms and explicitly approve it before generating a payment link or initiating PayPal Sandbox payment.`,
        }
      : null
  );

  const [isEditing, setIsEditing] = useState(false);
  const [editValues, setEditValues] = useState<InvoiceDraftFormValues>({
    clientName: '',
    clientEmail: '',
    clientPhone: '',
    description: '',
    amount: '',
    currency: 'USD',
    dueDate: '',
  });
  const [editErrors, setEditErrors] = useState<InvoiceDraftFormErrors>({});
  const [isSavingEdit, setIsSavingEdit] = useState(false);

  const [modalState, setModalState] = useState<ActiveModalState>(null);
  const [isActionBusy, setIsActionBusy] = useState(false);
  const [busyPaymentOrderId, setBusyPaymentOrderId] = useState<string | null>(null);
  const [isRedirectingToPayPal, setIsRedirectingToPayPal] = useState(false);
  const [isProcessingPayPalCallback, setIsProcessingPayPalCallback] = useState(false);

  const loadSeqRef = useRef(0);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const fetchInvoiceAndPayments = useCallback(
    async (background = false) => {
      if (!isValidRouteId) {
        setIsLoading(false);
        setLoadError('Invalid invoice identifier in URL.');
        return;
      }

      const seq = ++loadSeqRef.current;
      if (background) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setLoadError(null);

      try {
        const [invData, payData, linkData, deliveryData] = await Promise.all([
          invoicesApi.getInvoice(numericInvoiceId),
          paymentsApi.getPaymentHistory(numericInvoiceId),
          paymentLinksApi.listForInvoice(numericInvoiceId),
          emailApi.listDeliveries(numericInvoiceId),
        ]);
        if (!mountedRef.current || seq !== loadSeqRef.current) {
          return;
        }
        setInvoice(invData);
        setPayments(payData);
        setPaymentLinks(linkData);
        setDeliveries(deliveryData);
        const activeLink = linkData.find((l) => l.active);
        setCachedShareLink(
          activeLink
            ? loadCachedShareLink(user?.id, numericInvoiceId, activeLink.id)
            : null
        );
      } catch (error) {
        if (!mountedRef.current || seq !== loadSeqRef.current) {
          return;
        }
        const message =
          error instanceof ApiClientError
            ? error.message
            : 'Unable to load invoice details.';
        setLoadError(message);
      } finally {
        if (mountedRef.current && seq === loadSeqRef.current) {
          setIsLoading(false);
          setIsRefreshing(false);
        }
      }
    },
    [isValidRouteId, numericInvoiceId, user?.id]
  );

  useEffect(() => {
    void fetchInvoiceAndPayments(false);
  }, [fetchInvoiceAndPayments]);

  const stripPayPalQueryParams = useCallback(() => {
    const next = new URLSearchParams(searchParams);
    let changed = false;
    for (const key of ['paypal', 'token', 'PayerID', 'ba_token']) {
      if (next.has(key)) {
        next.delete(key);
        changed = true;
      }
    }
    if (changed) {
      setSearchParams(next, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  // Handle PayPal Sandbox checkout return (?paypal=return&token=...) and cancel (?paypal=cancel&token=...)
  useEffect(() => {
    if (!isValidRouteId) {
      return;
    }

    const modeParam = searchParams.get('paypal')?.toLowerCase() ?? null;
    if (modeParam !== 'return' && modeParam !== 'cancel') {
      return;
    }

    const tokenParam = searchParams.get('token')?.trim() ?? null;
    const callbackKey = `${numericInvoiceId}:${modeParam}:${tokenParam ?? 'missing'}`;
    if (handledPayPalCallbackKeys.has(callbackKey)) {
      return;
    }
    handledPayPalCallbackKeys.add(callbackKey);

    const runCallback = async () => {
      if (mountedRef.current) {
        setIsProcessingPayPalCallback(true);
      }

      try {
        if (modeParam === 'return') {
          if (!isValidPayPalOrderToken(tokenParam)) {
            if (mountedRef.current) {
              setFeedback({
                tone: 'error',
                title: 'Missing PayPal order token',
                message:
                  'PayPal Sandbox returned without a valid order token. Check payment history below before retrying.',
              });
            }
            return;
          }

          if (mountedRef.current) {
            setBusyPaymentOrderId(tokenParam);
          }

          try {
            const captureResponse = await paymentsApi.captureOrder(tokenParam);
            if (mountedRef.current) {
              setFeedback(describeCaptureOutcome(captureResponse));
            }
          } catch (error) {
            if (mountedRef.current) {
              setFeedback(describePaymentFailure(error, tokenParam));
            }
          } finally {
            if (mountedRef.current) {
              setBusyPaymentOrderId(null);
            }
          }
        } else {
          // Cancel return from PayPal Sandbox
          let orderIdToCancel: string | null = isValidPayPalOrderToken(tokenParam)
            ? tokenParam
            : null;

          if (!orderIdToCancel) {
            try {
              const history = await paymentsApi.getPaymentHistory(numericInvoiceId);
              const active = history.find(
                (item) => item.status === 'CREATED' || item.status === 'APPROVED'
              );
              orderIdToCancel = active?.paypalOrderId ?? null;
            } catch {
              // Fall back cleanly to general cancellation message
            }
          }

          if (!orderIdToCancel) {
            if (mountedRef.current) {
              setFeedback({
                tone: 'info',
                title: 'PayPal Sandbox checkout cancelled',
                message: 'Checkout was cancelled before payment capture.',
              });
            }
          } else {
            try {
              const cancelResponse = await paymentsApi.cancelOrder(orderIdToCancel, {
                reason: 'Checkout cancelled by user on PayPal Sandbox',
              });
              if (mountedRef.current) {
                setFeedback({
                  tone: 'info',
                  title: 'PayPal Sandbox checkout cancelled',
                  message: cancelResponse.paypalCancellationConfirmed
                    ? `Order ${cancelResponse.paypalOrderId} cancellation was confirmed by PayPal.`
                    : `Order ${cancelResponse.paypalOrderId} was cancelled locally in PayPilot AI (remote PayPal order cancellation was not separately confirmed).`,
                });
              }
            } catch (error) {
              if (mountedRef.current) {
                setFeedback(describePaymentFailure(error, orderIdToCancel));
              }
            }
          }
        }

        await fetchInvoiceAndPayments(true);
      } finally {
        if (mountedRef.current) {
          stripPayPalQueryParams();
          setIsProcessingPayPalCallback(false);
        }
      }
    };

    void runCallback();
  }, [
    isValidRouteId,
    numericInvoiceId,
    searchParams,
    fetchInvoiceAndPayments,
    stripPayPalQueryParams,
  ]);

  const openEditForm = () => {
    if (!invoice) {
      return;
    }
    setEditValues(toFormValues(invoice));
    setEditErrors({});
    setIsEditing(true);
  };

  const handleSaveEdit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!invoice || isSavingEdit || isActionBusy) {
      return;
    }

    const validation = validateInvoiceDraftForm(editValues);
    if (Object.keys(validation).length > 0) {
      setEditErrors(validation);
      return;
    }

    setEditErrors({});
    setIsSavingEdit(true);

    const wasApproved = invoice.status === 'APPROVED';
    const normalizedPhone = normalizeAndValidateOptionalPhone(editValues.clientPhone).normalized;
    const payload: UpdateInvoiceRequest = {
      clientName: editValues.clientName.trim(),
      clientEmail: editValues.clientEmail.trim(),
      clientPhone: normalizedPhone,
      customerId: invoice.customerId ?? undefined,
      description: editValues.description.trim(),
      amount: Number(editValues.amount.trim()),
      currency: editValues.currency.trim().toUpperCase(),
      dueDate: editValues.dueDate.trim(),
    };

    try {
      const updated = await invoicesApi.updateInvoice(invoice.id, payload);
      setInvoice(updated);
      setIsEditing(false);
      if (wasApproved) {
        removeCachedShareLink(user?.id, invoice.id);
        setCachedShareLink(null);
      }
      await fetchInvoiceAndPayments(true);
      setFeedback({
        tone: 'success',
        title: wasApproved ? 'Invoice updated and returned to DRAFT' : 'Draft invoice updated',
        message: wasApproved
          ? `Changes invalidated the prior approval snapshot and revoked any active payment link. Invoice ${updated.invoiceNumber} is now in DRAFT status.`
          : `Updated invoice ${updated.invoiceNumber} details successfully.`,
      });
    } catch (error) {
      if (error instanceof ApiClientError) {
        if (Object.keys(error.fieldErrors).length > 0) {
          setEditErrors({
            clientName: error.fieldErrors.clientName,
            clientEmail: error.fieldErrors.clientEmail,
            clientPhone: error.fieldErrors.clientPhone,
            description: error.fieldErrors.description,
            amount: error.fieldErrors.amount,
            currency: error.fieldErrors.currency,
            dueDate: error.fieldErrors.dueDate,
          });
        }
        setFeedback(describePaymentFailure(error));
        if (error.status === 409) {
          setIsEditing(false);
          await fetchInvoiceAndPayments(true);
        }
      } else {
        setFeedback({
          tone: 'error',
          title: 'Update failed',
          message: 'Unable to update this invoice right now.',
        });
      }
    } finally {
      setIsSavingEdit(false);
    }
  };

  const handleConfirmApprove = async () => {
    if (!invoice || isActionBusy) {
      return;
    }
    setIsActionBusy(true);
    try {
      const approved = await invoicesApi.approveInvoice(invoice.id, {
        snapshotHash: invoice.snapshotHash,
        confirmApproval: true,
      });
      setInvoice(approved);
      setModalState(null);
      await fetchInvoiceAndPayments(true);
      setFeedback({
        tone: 'success',
        title: 'Invoice approved',
        message: `Invoice ${approved.invoiceNumber} snapshot verified and marked APPROVED. You may now proceed with PayPal Sandbox checkout when ready.`,
      });
    } catch (error) {
      setModalState(null);
      setFeedback(describePaymentFailure(error));
      await fetchInvoiceAndPayments(true);
    } finally {
      setIsActionBusy(false);
    }
  };

  const handleConfirmRevoke = async () => {
    if (!invoice || isActionBusy) {
      return;
    }
    setIsActionBusy(true);
    try {
      const revoked = await invoicesApi.revokeApproval(invoice.id);
      setInvoice(revoked);
      removeCachedShareLink(user?.id, invoice.id);
      setCachedShareLink(null);
      setModalState(null);
      await fetchInvoiceAndPayments(true);
      setFeedback({
        tone: 'info',
        title: 'Approval revoked',
        message: `Invoice ${revoked.invoiceNumber} returned to DRAFT status, any active payment link was revoked, and uncaptured PayPal orders were invalidated locally.`,
      });
    } catch (error) {
      setModalState(null);
      setFeedback(describePaymentFailure(error));
      await fetchInvoiceAndPayments(true);
    } finally {
      setIsActionBusy(false);
    }
  };

  const handleConfirmDeleteDraft = async () => {
    if (!invoice || isActionBusy) {
      return;
    }
    setIsActionBusy(true);
    try {
      await invoicesApi.deleteDraftInvoice(invoice.id);
      navigate('/invoices', { replace: true });
    } catch (error) {
      setModalState(null);
      setFeedback(describePaymentFailure(error));
      await fetchInvoiceAndPayments(true);
    } finally {
      setIsActionBusy(false);
    }
  };

  const redirectToValidatedSandboxUrl = (rawUrl: string | null | undefined) => {
    const safeUrl = parsePayPalSandboxApprovalUrl(rawUrl);
    if (!safeUrl) {
      setFeedback({
        tone: 'error',
        title: 'Blocked unsafe redirect URL',
        message:
          'PayPal order response did not contain a valid HTTPS PayPal Sandbox approval URL. Redirection aborted for security.',
      });
      return;
    }
    setIsRedirectingToPayPal(true);
    window.location.assign(safeUrl);
  };

  const handlePayWithPayPalSandbox = async () => {
    if (!invoice || isActionBusy || isRedirectingToPayPal) {
      return;
    }
    setIsActionBusy(true);

    try {
      const { returnUrl, cancelUrl } = buildPayPalRedirectUrls(invoice.id);
      const order = await paymentsApi.createOrder({
        invoiceId: invoice.id,
        returnUrl,
        cancelUrl,
      });

      const safeUrl = parsePayPalSandboxApprovalUrl(order.approvalUrl);
      if (!safeUrl) {
        await fetchInvoiceAndPayments(true);
        setFeedback({
          tone: 'error',
          title: 'Invalid PayPal Sandbox checkout link',
          message:
            'The backend response did not include a valid HTTPS PayPal Sandbox URL. Navigation blocked.',
        });
        return;
      }

      setIsRedirectingToPayPal(true);
      window.location.assign(safeUrl);
    } catch (error) {
      setFeedback(describePaymentFailure(error));
      await fetchInvoiceAndPayments(true);
    } finally {
      setIsActionBusy(false);
    }
  };

  const handleCheckPaymentStatus = async (orderId: string) => {
    if (busyPaymentOrderId || isActionBusy) {
      return;
    }
    setBusyPaymentOrderId(orderId);
    try {
      const result = await paymentsApi.captureOrder(orderId);
      setFeedback(describeCaptureOutcome(result));
      await fetchInvoiceAndPayments(true);
    } catch (error) {
      setFeedback(describePaymentFailure(error, orderId));
      await fetchInvoiceAndPayments(true);
    } finally {
      setBusyPaymentOrderId(null);
    }
  };

  const handleConfirmCancelOrder = async (orderId: string) => {
    if (busyPaymentOrderId || isActionBusy) {
      return;
    }
    setBusyPaymentOrderId(orderId);
    try {
      const cancelled = await paymentsApi.cancelOrder(orderId, {
        reason: 'Cancelled from invoice payment controls',
      });
      setModalState(null);
      setFeedback({
        tone: 'info',
        title: 'Payment order cancelled',
        message: cancelled.paypalCancellationConfirmed
          ? `Order ${cancelled.paypalOrderId} cancellation confirmed.`
          : `Order ${cancelled.paypalOrderId} was marked CANCELLED locally in PayPilot AI (remote PayPal order cancellation was not separately confirmed).`,
      });
      await fetchInvoiceAndPayments(true);
    } catch (error) {
      setModalState(null);
      setFeedback(describePaymentFailure(error, orderId));
      await fetchInvoiceAndPayments(true);
    } finally {
      setBusyPaymentOrderId(null);
    }
  };

  const handleGenerateOrRotateLink = async (expiresInDays: number) => {
    if (!invoice || isLinkBusy || isActionBusy) {
      return;
    }
    setIsLinkBusy(true);
    try {
      const generated = await paymentLinksApi.generateOrRotate(invoice.id, { expiresInDays });
      const cached = saveCachedShareLink(user?.id, generated);
      setCachedShareLink(cached);
      await fetchInvoiceAndPayments(true);
      setFeedback({
        tone: 'success',
        title: 'Secure customer payment link ready',
        message: `Generated single-active link (${generated.link.tokenPrefix}) expiring ${formatDisplayDate(generated.link.expiresAt)}. Copy or share it with ${invoice.clientName}.`,
      });
    } catch (error) {
      setFeedback(describePaymentFailure(error));
    } finally {
      setIsLinkBusy(false);
    }
  };

  const handleRevokeActiveLink = async (reason: string) => {
    if (!invoice || isLinkBusy || isActionBusy) {
      return;
    }
    setIsLinkBusy(true);
    try {
      const revoked = await paymentLinksApi.revokeActive(invoice.id, { reason });
      removeCachedShareLink(user?.id, invoice.id);
      setCachedShareLink(null);
      await fetchInvoiceAndPayments(true);
      setFeedback({
        tone: 'info',
        title: 'Payment link revoked',
        message: `Link ${revoked.tokenPrefix} has been revoked and can no longer be used for customer checkout.`,
      });
    } catch (error) {
      setFeedback(describePaymentFailure(error));
    } finally {
      setIsLinkBusy(false);
    }
  };

  const handleEmailSent = async (sent: InvoiceEmailSendResponse) => {
    setIsEmailModalOpen(false);
    setFeedback({
      tone: 'success',
      title: 'Invoice email sent',
      message: `Dispatched payment link (${sent.tokenPrefix}) to ${sent.recipientEmail}.`,
    });
    await fetchInvoiceAndPayments(true);
  };

  if (isLoading && !invoice) {
    return (
      <div className="rounded-2xl border border-slate-200 bg-white p-10 text-center text-sm text-slate-600 shadow-sm">
        Loading invoice details and payment history...
      </div>
    );
  }

  if (loadError && !invoice) {
    return (
      <div className="space-y-4">
        <AlertBanner tone="error" title="Could not load invoice" message={loadError} />
        <div className="flex flex-wrap gap-3">
          <Link
            to="/invoices"
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Back to Invoices
          </Link>
          {isValidRouteId ? (
            <button
              type="button"
              onClick={() => void fetchInvoiceAndPayments(false)}
              className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
            >
              Retry
            </button>
          ) : null}
        </div>
      </div>
    );
  }

  if (!invoice) {
    return null;
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link
          to="/invoices"
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
        >
          &larr; Back to Invoices
        </Link>

        <button
          type="button"
          onClick={() => void fetchInvoiceAndPayments(true)}
          disabled={isRefreshing || isActionBusy || isProcessingPayPalCallback}
          className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
        >
          <RefreshIcon className={`h-3.5 w-3.5 ${isRefreshing ? 'animate-spin' : ''}`} />
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>

      {isProcessingPayPalCallback ? (
        <AlertBanner
          tone="info"
          title="Processing PayPal Sandbox return..."
          message="Verifying transaction details securely with the PayPilot backend. Do not close or refresh this window."
        />
      ) : null}

      <PaymentFeedbackBanner
        feedback={feedback}
        busyOrderId={busyPaymentOrderId}
        onCheckStatus={(orderId) => void handleCheckPaymentStatus(orderId)}
      />

      {loadError ? (
        <AlertBanner tone="warning" title="Refresh warning" message={loadError} />
      ) : null}

      {/* Core invoice detail card */}
      <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex flex-col justify-between gap-4 border-b border-slate-100 pb-5 sm:flex-row sm:items-start">
          <div>
            <div className="flex flex-wrap items-center gap-2.5">
              <h2 className="text-2xl font-semibold tracking-tight text-slate-900">
                {invoice.invoiceNumber}
              </h2>
              <InvoiceStatusBadge status={invoice.status} />
              {invoice.aiExtracted ? (
                <span className="ai-label">
                  AI-Extracted Draft
                  {invoice.aiConfidence !== null
                    ? ` · ${Math.round(invoice.aiConfidence * 100)}%`
                    : ''}
                </span>
              ) : null}
            </div>
            <p className="mt-1 text-xs text-slate-500">
              Created {formatDisplayDate(invoice.createdAt)} · Last updated{' '}
              {formatDisplayDate(invoice.updatedAt)}
            </p>
          </div>

          <div className="text-left sm:text-right">
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Invoice Total
            </p>
            <p className="mt-1 text-2xl font-semibold tabular-nums text-slate-900">
              {formatInvoiceMoney(invoice.amount, invoice.currency)}
            </p>
          </div>
        </div>

        <dl className="mt-5 grid gap-5 sm:grid-cols-2 lg:grid-cols-5">
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Client Name
            </dt>
            <dd className="mt-1 text-sm font-medium text-slate-900">{invoice.clientName}</dd>
          </div>
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Client Email
            </dt>
            <dd className="mt-1 text-sm text-slate-700 break-all">{invoice.clientEmail}</dd>
          </div>
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Client Phone
            </dt>
            <dd className="mt-1 text-sm text-slate-700">
              {invoice.clientPhone ? (
                <span className="font-mono">{invoice.clientPhone}</span>
              ) : (
                <Link
                  to="/customers"
                  className="text-xs font-medium text-primary-600 hover:underline"
                >
                  Add phone in Customers
                </Link>
              )}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Due Date
            </dt>
            <dd className="mt-1 text-sm font-medium text-slate-900">
              {formatDisplayDate(invoice.dueDate)}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              {invoice.paidAt ? 'Paid Date' : 'Currency'}
            </dt>
            <dd className="mt-1 text-sm font-medium text-slate-900">
              {invoice.paidAt ? formatDisplayDate(invoice.paidAt) : invoice.currency}
            </dd>
          </div>
        </dl>

        <div className="mt-5 border-t border-slate-100 pt-5">
          <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
            Description &amp; Scope
          </p>
          <p className="mt-1.5 whitespace-pre-wrap text-sm leading-relaxed text-slate-700">
            {invoice.description}
          </p>
        </div>
      </section>

      {/* Inline edit form when editing DRAFT or APPROVED (after revocation warning) */}
      {isEditing ? (
        <section className="rounded-2xl border border-primary-100 bg-white p-6 shadow-sm">
          <InvoiceDraftForm
            values={editValues}
            errors={editErrors}
            isAiGenerated={false}
            hasConfirmedReview={true}
            isSaving={isSavingEdit}
            title={
              invoice.status === 'APPROVED'
                ? 'Edit Invoice (Invalidates Approval & Returns to DRAFT)'
                : 'Edit Draft Invoice'
            }
            subtitle={
              invoice.status === 'APPROVED'
                ? 'Saving changes will revoke current approval and mark any uncaptured PayPal orders unusable.'
                : 'Update invoice fields before submitting for approval.'
            }
            modeBadgeLabel={
              invoice.status === 'APPROVED' ? 'Approval Will Be Revoked' : 'Editing DRAFT'
            }
            submitLabel={
              invoice.status === 'APPROVED' ? 'Save & Return to DRAFT' : 'Save Changes'
            }
            onChangeField={(field, value) => {
              setEditValues((prev) => ({ ...prev, [field]: value }));
              setEditErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev));
            }}
            onToggleConfirmReview={() => {}}
            onSubmit={handleSaveEdit}
            onCancel={() => {
              setIsEditing(false);
              setEditErrors({});
            }}
          />
        </section>
      ) : (
        <InvoiceApprovalPanel
          invoice={invoice}
          payments={payments}
          isBusy={isActionBusy || isProcessingPayPalCallback || Boolean(busyPaymentOrderId)}
          isRedirectingToPayPal={isRedirectingToPayPal}
          onStartEditDraft={openEditForm}
          onRequestDeleteDraft={() => setModalState({ type: 'delete' })}
          onRequestApprove={() => setModalState({ type: 'approve' })}
          onRequestEditApproved={() => setModalState({ type: 'confirm-edit-approved' })}
          onRequestRevokeApproval={() => setModalState({ type: 'revoke' })}
          onPayWithPayPalSandbox={() => void handlePayWithPayPalSandbox()}
          onCheckPaymentStatus={(orderId) => void handleCheckPaymentStatus(orderId)}
        />
      )}

      {!isEditing ? (
        <PaymentLinkManagerPanel
          invoice={invoice}
          links={paymentLinks}
          cachedShareLink={cachedShareLink}
          hasPendingOrCapturedPayment={payments.some(
            (p) => p.status === 'PENDING' || p.status === 'CAPTURED'
          )}
          isBusy={isLinkBusy || isActionBusy || isProcessingPayPalCallback}
          onGenerateOrRotate={handleGenerateOrRotateLink}
          onRevokeActive={handleRevokeActiveLink}
          onShareByEmail={() => setIsEmailModalOpen(true)}
          onShareByWhatsApp={() => setIsWhatsAppModalOpen(true)}
        />
      ) : null}

      {deliveries.length > 0 ? (
        <section className="space-y-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              Email Delivery History ({deliveries.length})
            </h3>
            <p className="text-xs text-slate-500">
              Audit trail of seller-initiated email notifications for this invoice.
            </p>
          </div>
          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
            <ul className="divide-y divide-slate-100">
              {deliveries.map((entry) => (
                <li
                  key={entry.id}
                  className="flex flex-col justify-between gap-2 px-5 py-3.5 text-xs sm:flex-row sm:items-center"
                >
                  <div className="space-y-0.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <span
                        className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold ${
                          entry.status === 'SENT'
                            ? 'bg-emerald-100 text-emerald-800'
                            : 'bg-red-100 text-red-700'
                        }`}
                      >
                        {entry.status}
                      </span>
                      <span className="font-semibold text-slate-900">{entry.channel}</span>
                      <span className="text-slate-600">to {entry.recipient}</span>
                    </div>
                    {entry.subject ? (
                      <p className="text-slate-500">{entry.subject}</p>
                    ) : null}
                    {entry.failureReason ? (
                      <p className="text-red-600">{entry.failureReason}</p>
                    ) : null}
                  </div>
                  <span className="shrink-0 text-slate-500">
                    {formatDisplayDate(entry.createdAt)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </section>
      ) : null}

      {/* Invoice Payment History */}
      <section className="space-y-3">
        <div>
          <h3 className="text-base font-semibold text-slate-900">
            Payment History ({payments.length})
          </h3>
          <p className="text-xs text-slate-500">
            Server-persisted PayPal Sandbox order and capture records for this invoice.
          </p>
        </div>

        {payments.length === 0 ? (
          <div className="rounded-xl border border-dashed border-slate-300 bg-white p-8 text-center text-sm text-slate-500">
            No PayPal Sandbox payment orders have been initiated for this invoice yet.
          </div>
        ) : (
          <PaymentHistoryTable
            payments={payments}
            busyOrderId={busyPaymentOrderId}
            onCheckStatus={(orderId) => void handleCheckPaymentStatus(orderId)}
            onCancelOrder={(orderId) => setModalState({ type: 'cancel-order', orderId })}
            onResumeCheckout={redirectToValidatedSandboxUrl}
          />
        )}
      </section>

      <SendInvoiceEmailModal
        open={isEmailModalOpen}
        invoice={invoice}
        shareLink={cachedShareLink}
        onClose={() => setIsEmailModalOpen(false)}
        onEmailSent={handleEmailSent}
      />

      <ShareWhatsAppModal
        open={isWhatsAppModalOpen}
        invoice={invoice}
        shareLink={cachedShareLink}
        onClose={() => setIsWhatsAppModalOpen(false)}
        onPhoneSaved={() => fetchInvoiceAndPayments(true)}
      />

      {/* Confirmation Dialogs */}
      <ConfirmDialog
        open={modalState?.type === 'approve'}
        title="Confirm Explicit Invoice Approval"
        description="You are approving the exact invoice snapshot below. Once approved, this invoice becomes eligible for PayPal Sandbox payment."
        confirmLabel={isActionBusy ? 'Approving...' : 'Confirm & Approve Invoice'}
        tone="primary"
        isBusy={isActionBusy}
        details={[
          { label: 'Invoice Number', value: invoice.invoiceNumber },
          { label: 'Client', value: `${invoice.clientName} (${invoice.clientEmail})` },
          { label: 'Total Amount', value: formatInvoiceMoney(invoice.amount, invoice.currency) },
          { label: 'Due Date', value: formatDisplayDate(invoice.dueDate) },
          { label: 'Snapshot Hash', value: invoice.snapshotHash },
        ]}
        warningNote="Approving does NOT create a PayPal order automatically. Any subsequent edit will invalidate this approval."
        onConfirm={() => void handleConfirmApprove()}
        onCancel={() => setModalState(null)}
      />

      <ConfirmDialog
        open={modalState?.type === 'revoke'}
        title="Revoke Invoice Approval?"
        description={`Revoking approval returns ${invoice.invoiceNumber} to DRAFT status and clears its approval metadata.`}
        confirmLabel={isActionBusy ? 'Revoking...' : 'Confirm Revoke Approval'}
        tone="warning"
        isBusy={isActionBusy}
        warningNote="Any uncaptured PayPal orders associated with this approval will become unusable in PayPilot AI."
        onConfirm={() => void handleConfirmRevoke()}
        onCancel={() => setModalState(null)}
      />

      <ConfirmDialog
        open={modalState?.type === 'confirm-edit-approved'}
        title="Edit Approved Invoice?"
        description={`Invoice ${invoice.invoiceNumber} is currently APPROVED. Proceeding to edit will invalidate the existing approval snapshot when changes are saved.`}
        confirmLabel="Proceed to Edit"
        tone="warning"
        warningNote="Saving updates will return the invoice to DRAFT status and mark any uncaptured PayPal orders as locally invalidated."
        onConfirm={() => {
          setModalState(null);
          openEditForm();
        }}
        onCancel={() => setModalState(null)}
      />

      <ConfirmDialog
        open={modalState?.type === 'delete'}
        title="Delete Draft Invoice?"
        description={`This permanently removes DRAFT invoice ${invoice.invoiceNumber}. Only unapproved drafts with no payment history can be deleted.`}
        confirmLabel={isActionBusy ? 'Deleting...' : 'Delete Draft'}
        tone="danger"
        isBusy={isActionBusy}
        onConfirm={() => void handleConfirmDeleteDraft()}
        onCancel={() => setModalState(null)}
      />

      <ConfirmDialog
        open={modalState?.type === 'cancel-order'}
        title="Cancel Payment Order Locally?"
        description={
          modalState?.type === 'cancel-order'
            ? `Cancel uncaptured order ${modalState.orderId} locally in PayPilot AI?`
            : ''
        }
        confirmLabel={busyPaymentOrderId ? 'Cancelling...' : 'Confirm Local Cancel'}
        tone="danger"
        isBusy={Boolean(busyPaymentOrderId)}
        warningNote="Note: PayPilot records local cancellation cleanly if capture has not started; remote PayPal order cancellation is not guaranteed."
        onConfirm={() => {
          if (modalState?.type === 'cancel-order') {
            void handleConfirmCancelOrder(modalState.orderId);
          }
        }}
        onCancel={() => setModalState(null)}
      />
    </div>
  );
}
