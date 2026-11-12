import { apiClient } from './client';
import type {
  InvoiceDeliveryLogResponse,
  InvoiceEmailPreviewResponse,
  InvoiceEmailRequest,
  InvoiceEmailSendResponse,
} from '@/types';

/**
 * Seller-authenticated invoice email preview, dispatch, and delivery audit API.
 */
export const emailApi = {
  async previewInvoiceEmail(
    invoiceId: number,
    request: InvoiceEmailRequest
  ): Promise<InvoiceEmailPreviewResponse> {
    const { data } = await apiClient.post<InvoiceEmailPreviewResponse>(
      `/invoices/${invoiceId}/email/preview`,
      request
    );
    return data;
  },

  async sendInvoiceEmail(
    invoiceId: number,
    request: InvoiceEmailRequest
  ): Promise<InvoiceEmailSendResponse> {
    const { data } = await apiClient.post<InvoiceEmailSendResponse>(
      `/invoices/${invoiceId}/email/send`,
      request
    );
    return data;
  },

  async listDeliveries(invoiceId: number): Promise<InvoiceDeliveryLogResponse[]> {
    const { data } = await apiClient.get<InvoiceDeliveryLogResponse[]>(
      `/invoices/${invoiceId}/deliveries`
    );
    return data;
  },
};

