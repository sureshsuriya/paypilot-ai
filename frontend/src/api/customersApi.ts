import { apiClient } from './client';
import type {
  CreateCustomerRequest,
  CustomerDetailResponse,
  CustomerResponse,
  UpdateCustomerRequest,
} from '@/types';

/**
 * Seller-authenticated customer directory API (`/api/customers`).
 */
export const customersApi = {
  async listCustomers(search?: string): Promise<CustomerResponse[]> {
    const { data } = await apiClient.get<CustomerResponse[]>('/customers', {
      params: search && search.trim() ? { search: search.trim() } : undefined,
    });
    return data;
  },

  async getCustomer(customerId: number): Promise<CustomerDetailResponse> {
    const { data } = await apiClient.get<CustomerDetailResponse>(`/customers/${customerId}`);
    return data;
  },

  async createCustomer(request: CreateCustomerRequest): Promise<CustomerResponse> {
    const { data } = await apiClient.post<CustomerResponse>('/customers', request);
    return data;
  },

  async updateCustomer(
    customerId: number,
    request: UpdateCustomerRequest
  ): Promise<CustomerResponse> {
    const { data } = await apiClient.put<CustomerResponse>(
      `/customers/${customerId}`,
      request
    );
    return data;
  },
};

