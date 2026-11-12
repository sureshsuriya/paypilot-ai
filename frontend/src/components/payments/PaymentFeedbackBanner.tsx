import { AlertBanner } from '@/components/ui/AlertBanner';
import type { PaymentFeedbackMessage } from '@/utils/paypal';

export interface PaymentFeedbackBannerProps {
  feedback: PaymentFeedbackMessage | null;
  busyOrderId?: string | null;
  onCheckStatus: (orderId: string) => void;
}

export function PaymentFeedbackBanner({
  feedback,
  busyOrderId = null,
  onCheckStatus,
}: PaymentFeedbackBannerProps) {
  if (!feedback) {
    return null;
  }

  return (
    <div className="space-y-2">
      <AlertBanner
        tone={feedback.tone}
        title={feedback.title}
        message={feedback.message}
      />
      {feedback.retryOrderId ? (
        <div className="flex justify-end">
          <button
            type="button"
            disabled={Boolean(busyOrderId)}
            onClick={() => onCheckStatus(feedback.retryOrderId!)}
            className="rounded-lg border border-amber-300 bg-amber-50 px-3.5 py-1.5 text-xs font-semibold text-amber-900 transition-colors hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {busyOrderId === feedback.retryOrderId
              ? 'Checking PayPal Status...'
              : 'Check Payment Status'}
          </button>
        </div>
      ) : null}
    </div>
  );
}

