export interface PaymentLinkSummaryResponse {
  id: number;
  invoiceId: number;
  tokenPrefix: string;
  active: boolean;
  expired: boolean;
  revoked: boolean;
  expiresAt: string;
  revokedAt: string | null;
  revocationReason: string | null;
  accessCount: number;
  lastAccessedAt: string | null;
  createdAt: string;
}

export interface GeneratedPaymentLinkResponse {
  link: PaymentLinkSummaryResponse;
  paymentUrl: string;
  rawToken: string;
}

export interface CreatePaymentLinkRequest {
  expiresInDays?: number | null;
}

export interface RevokePaymentLinkRequest {
  reason?: string | null;
}

export interface CachedShareLink {
  paymentLinkId: number;
  invoiceId: number;
  paymentUrl: string;
  rawToken: string;
  tokenPrefix: string;
  expiresAt: string;
}

