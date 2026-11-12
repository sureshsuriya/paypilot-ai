export type InvoiceStatus =
  | 'DRAFT'
  | 'APPROVED'
  | 'SENT'
  | 'PAID'
  | 'OVERDUE'
  | 'CANCELLED';

export interface InvoiceResponse {
  id: number;
  customerId: number | null;
  invoiceNumber: string;
  paypalInvoiceId: string | null;
  clientName: string;
  clientEmail: string;
  clientPhone: string | null;
  description: string;
  amount: number;
  currency: string;
  status: InvoiceStatus;
  dueDate: string | null;
  issuedAt: string | null;
  paidAt: string | null;
  aiExtracted: boolean;
  aiConfidence: number | null;
  snapshotHash: string;
  approvedAt: string | null;
  approvedByUserId: number | null;
  approvedSnapshotHash: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateInvoiceRequest {
  clientName: string;
  clientEmail: string;
  description: string;
  amount: number;
  currency: string;
  dueDate: string;
  extractionLogId?: number | null;
  customerId?: number | null;
  clientPhone?: string | null;
}

export interface UpdateInvoiceRequest {
  clientName: string;
  clientEmail: string;
  description: string;
  amount: number;
  currency: string;
  dueDate: string;
  customerId?: number | null;
  clientPhone?: string | null;
}

export interface ApproveInvoiceRequest {
  snapshotHash: string;
  confirmApproval: boolean;
}

