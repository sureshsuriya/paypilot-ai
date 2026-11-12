import { apiClient } from './client';
import type {
  ApproveInvoiceRequest,
  CreateInvoiceRequest,
  InvoiceResponse,
  InvoiceStatus,
  UpdateInvoiceRequest,
} from '@/types';

export const invoicesApi = {
  async listInvoices(status?: InvoiceStatus): Promise<InvoiceResponse[]> {
    const { data } = await apiClient.get<InvoiceResponse[]>('/invoices', {
      params: status ? { status } : undefined,
    });
    return data;
  },

  async getInvoice(invoiceId: number): Promise<InvoiceResponse> {
    const { data } = await apiClient.get<InvoiceResponse>(`/invoices/${invoiceId}`);
    return data;
  },

  async createInvoice(request: CreateInvoiceRequest): Promise<InvoiceResponse> {
    const { data } = await apiClient.post<InvoiceResponse>('/invoices', request);
    return data;
  },

  async updateInvoice(
    invoiceId: number,
    request: UpdateInvoiceRequest
  ): Promise<InvoiceResponse> {
    const { data } = await apiClient.put<InvoiceResponse>(`/invoices/${invoiceId}`, request);
    return data;
  },

  async approveInvoice(
    invoiceId: number,
    request: ApproveInvoiceRequest
  ): Promise<InvoiceResponse> {
    const { data } = await apiClient.post<InvoiceResponse>(
      `/invoices/${invoiceId}/approve`,
      request
    );
    return data;
  },

  async revokeApproval(invoiceId: number): Promise<InvoiceResponse> {
    const { data } = await apiClient.post<InvoiceResponse>(
      `/invoices/${invoiceId}/revoke-approval`
    );
    return data;
  },

  async deleteDraftInvoice(invoiceId: number): Promise<void> {
    await apiClient.delete(`/invoices/${invoiceId}`);
  },
};

