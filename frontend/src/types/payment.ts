import type { InvoiceStatus } from './invoice';

export type PaymentStatus =
  | 'CREATED'
  | 'APPROVED'
  | 'PENDING'
  | 'CAPTURED'
  | 'FAILED'
  | 'CANCELLED';

export interface CreatePaymentOrderRequest {
  invoiceId: number;
  returnUrl?: string | null;
  cancelUrl?: string | null;
}

export interface CancelPaymentRequest {
  reason?: string | null;
}

export interface PaymentResponse {
  id: number;
  invoiceId: number;
  invoiceNumber: string;
  clientName: string;
  invoiceStatus: InvoiceStatus;
  paymentLinkId: number | null;
  customerLinkCheckout: boolean;
  paypalOrderId: string;
  paypalCaptureId: string | null;
  status: PaymentStatus;
  amount: number;
  currency: string;
  approvalUrl: string | null;
  invoiceSnapshotHash: string | null;
  pendingReason: string | null;
  completed: boolean;
  pending: boolean;
  locallyCancelled: boolean;
  paypalCancellationConfirmed: boolean;
  reused: boolean;
  capturedAt: string | null;
  createdAt: string;
  updatedAt: string;
  summary: Record<string, unknown>;
}

