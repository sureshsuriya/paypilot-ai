import axios from 'axios';
import { normalizeApiError, resolveApiBaseUrl } from './client';
import type {
  PublicCancelOrderRequest,
  PublicCaptureOrderRequest,
  PublicCreateOrderRequest,
  PublicPaymentInvoiceResponse,
  PublicPaymentOrderResponse,
} from '@/types';

/**
 * Strictly unauthenticated Axios client dedicated to the public Customer Portal (`/api/public/pay/**`).
 * Never attaches Seller JWT headers, never reads seller auth storage, and never triggers seller logout hooks.
 */
export const publicApiClient = axios.create({
  baseURL: resolveApiBaseUrl(),
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 30000,
});

publicApiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => Promise.reject(normalizeApiError(error))
);

export interface GetPublicInvoiceOptions {
  trackView?: boolean;
}

const inFlightGetRequests = new Map<string, Promise<PublicPaymentInvoiceResponse>>();

export const publicPayApi = {
  async getPublicInvoice(
    token: string,
    options?: GetPublicInvoiceOptions
  ): Promise<PublicPaymentInvoiceResponse> {
    const trackView = options?.trackView ?? true;
    const dedupeKey = `${token}:${trackView ? '1' : '0'}`;

    const existingInFlight = inFlightGetRequests.get(dedupeKey);
    if (existingInFlight) {
      return existingInFlight;
    }

    const requestPromise = publicApiClient
      .get<PublicPaymentInvoiceResponse>(`/public/pay/${encodeURIComponent(token)}`, {
        params: trackView ? undefined : { trackView: false },
      })
      .then((res) => res.data)
      .finally(() => {
        if (inFlightGetRequests.get(dedupeKey) === requestPromise) {
          inFlightGetRequests.delete(dedupeKey);
        }
      });

    inFlightGetRequests.set(dedupeKey, requestPromise);
    return requestPromise;
  },

  async createOrder(
    token: string,
    request?: PublicCreateOrderRequest
  ): Promise<PublicPaymentOrderResponse> {
    const { data } = await publicApiClient.post<PublicPaymentOrderResponse>(
      `/public/pay/${encodeURIComponent(token)}/orders`,
      request ?? {}
    );
    return data;
  },

  async captureOrder(
    token: string,
    request: PublicCaptureOrderRequest
  ): Promise<PublicPaymentInvoiceResponse> {
    const { data } = await publicApiClient.post<PublicPaymentInvoiceResponse>(
      `/public/pay/${encodeURIComponent(token)}/capture`,
      request
    );
    return data;
  },

  async cancelOrder(
    token: string,
    request?: PublicCancelOrderRequest
  ): Promise<PublicPaymentInvoiceResponse> {
    const { data } = await publicApiClient.post<PublicPaymentInvoiceResponse>(
      `/public/pay/${encodeURIComponent(token)}/cancel`,
      request ?? {}
    );
    return data;
  },
};
