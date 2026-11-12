import type { InvoiceStatus } from './invoice';
import type { PaymentStatus } from './payment';

export type PublicLinkState =
  | 'ACTIVE'
  | 'PAID'
  | 'REVOKED'
  | 'EXPIRED'
  | 'UNAVAILABLE';

export interface PublicPaymentSummary {
  paypalOrderId: string;
  status: PaymentStatus;
  pendingReason: string | null;
  approvalUrl: string | null;
  updatedAt: string | null;
}

export interface PublicPaymentReceiptResponse {
  invoiceNumber: string;
  sellerDisplayName: string;
  customerName: string;
  description: string;
  amount: number;
  currency: string;
  paypalOrderId: string;
  paypalCaptureId: string;
  paymentStatus: PaymentStatus;
  paidAt: string | null;
  sandboxMode: boolean;
}

export interface PublicPaymentInvoiceResponse {
  linkState: PublicLinkState;
  payable: boolean;
  sandboxMode: boolean;
  statusMessage: string;
  expiresAt: string | null;
  invoiceNumber: string | null;
  sellerDisplayName: string | null;
  customerName: string | null;
  description: string | null;
  amount: number | null;
  currency: string | null;
  dueDate: string | null;
  invoiceStatus: InvoiceStatus | null;
  activePayment: PublicPaymentSummary | null;
  receipt: PublicPaymentReceiptResponse | null;
}

export interface PublicPaymentOrderResponse {
  paypalOrderId: string;
  status: PaymentStatus;
  amount: number;
  currency: string;
  approvalUrl: string | null;
  reused: boolean;
  sandboxMode: boolean;
}

export interface PublicCreateOrderRequest {
  returnUrl?: string | null;
  cancelUrl?: string | null;
}

export interface PublicCaptureOrderRequest {
  orderId: string;
}

export interface PublicCancelOrderRequest {
  orderId?: string | null;
  reason?: string | null;
}

