import type { InvoiceResponse } from './invoice';

export interface CustomerResponse {
  id: number;
  name: string;
  email: string;
  phone: string | null;
  company: string | null;
  notes: string | null;
  invoiceCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CustomerDetailResponse {
  customer: CustomerResponse;
  invoices: InvoiceResponse[];
}

export interface CreateCustomerRequest {
  name: string;
  email: string;
  phone?: string | null;
  company?: string | null;
  notes?: string | null;
}

export interface UpdateCustomerRequest {
  name: string;
  email: string;
  phone?: string | null;
  company?: string | null;
  notes?: string | null;
}

