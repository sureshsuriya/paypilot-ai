import { apiClient } from './client';
import type {
  CreatePaymentLinkRequest,
  GeneratedPaymentLinkResponse,
  PaymentLinkSummaryResponse,
  RevokePaymentLinkRequest,
} from '@/types';

/**
 * Seller-authenticated payment-link management API (`/api/invoices/{invoiceId}/payment-links`).
 */
export const paymentLinksApi = {
  async generateOrRotate(
    invoiceId: number,
    request?: CreatePaymentLinkRequest
  ): Promise<GeneratedPaymentLinkResponse> {
    const { data } = await apiClient.post<GeneratedPaymentLinkResponse>(
      `/invoices/${invoiceId}/payment-links`,
      request ?? {}
    );
    return data;
  },

  async listForInvoice(invoiceId: number): Promise<PaymentLinkSummaryResponse[]> {
    const { data } = await apiClient.get<PaymentLinkSummaryResponse[]>(
      `/invoices/${invoiceId}/payment-links`
    );
    return data;
  },

  async revokeActive(
    invoiceId: number,
    request?: RevokePaymentLinkRequest
  ): Promise<PaymentLinkSummaryResponse> {
    const { data } = await apiClient.post<PaymentLinkSummaryResponse>(
      `/invoices/${invoiceId}/payment-links/revoke`,
      request ?? {}
    );
    return data;
  },
};

