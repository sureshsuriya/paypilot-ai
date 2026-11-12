export interface InvoiceEmailRequest {
  rawToken: string;
  recipientEmail?: string | null;
  customMessage?: string | null;
}

export interface InvoiceEmailPreviewResponse {
  recipientEmail: string;
  recipientName: string;
  subject: string;
  htmlBody: string;
  textBody: string;
  paymentUrl: string;
  linkExpiresAt: string;
  mailConfigured: boolean;
}

export interface InvoiceEmailSendResponse {
  deliveryId: number;
  invoiceId: number;
  recipientEmail: string;
  subject: string;
  tokenPrefix: string;
  sentAt: string;
  sandboxMode: boolean;
}

export interface InvoiceDeliveryLogResponse {
  id: number;
  invoiceId: number;
  channel: string;
  recipient: string;
  subject: string | null;
  status: string;
  failureReason: string | null;
  createdAt: string;
}

