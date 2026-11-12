import { apiClient } from './client';
import type {
  CancelPaymentRequest,
  CreatePaymentOrderRequest,
  PaymentResponse,
  PaymentStatus,
} from '@/types';

/**
 * Payment orchestration API client targeting Spring Boot `/api/payments/*`.
 * All PayPal Sandbox order creation and capture verification stays server-side.
 */
export const paymentsApi = {
  async listAllPayments(status?: PaymentStatus): Promise<PaymentResponse[]> {
    const { data } = await apiClient.get<PaymentResponse[]>('/payments', {
      params: status ? { status } : undefined,
    });
    return data;
  },

  async createOrder(request: CreatePaymentOrderRequest): Promise<PaymentResponse> {
    const { data } = await apiClient.post<PaymentResponse>('/payments/orders', request);
    return data;
  },

  async captureOrder(orderId: string): Promise<PaymentResponse> {
    const { data } = await apiClient.post<PaymentResponse>(
      `/payments/orders/${encodeURIComponent(orderId)}/capture`
    );
    return data;
  },

  async cancelOrder(
    orderId: string,
    request?: CancelPaymentRequest
  ): Promise<PaymentResponse> {
    const { data } = await apiClient.post<PaymentResponse>(
      `/payments/orders/${encodeURIComponent(orderId)}/cancel`,
      request
    );
    return data;
  },

  async getPaymentHistory(invoiceId: number): Promise<PaymentResponse[]> {
    const { data } = await apiClient.get<PaymentResponse[]>(
      `/payments/invoice/${invoiceId}`
    );
    return data;
  },
};

