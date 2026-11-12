import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { emailApi } from '@/api';
import { SendInvoiceEmailModal } from '@/components/invoices/SendInvoiceEmailModal';
import {
  ApiClientError,
  type CachedShareLink,
  type InvoiceResponse,
} from '@/types';

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api')>();
  return {
    ...actual,
    emailApi: {
      previewInvoiceEmail: vi.fn(),
      sendInvoiceEmail: vi.fn(),
      listDeliveries: vi.fn(),
    },
  };
});

const SAMPLE_INVOICE: InvoiceResponse = {
  id: 10,
  customerId: 4,
  invoiceNumber: 'INV-20261112-EM01',
  paypalInvoiceId: null,
  clientName: 'Taylor Nguyen',
  clientEmail: 'taylor@client.test',
  clientPhone: '+14155552671',
  description: 'Website development sprint',
  amount: 750,
  currency: 'USD',
  status: 'APPROVED',
  dueDate: '2026-11-28',
  issuedAt: '2026-11-12',
  paidAt: null,
  aiExtracted: false,
  aiConfidence: null,
  snapshotHash: 'a'.repeat(64),
  approvedAt: '2026-11-12T10:00:00',
  approvedByUserId: 1,
  approvedSnapshotHash: 'a'.repeat(64),
  createdAt: '2026-11-12T09:50:00',
  updatedAt: '2026-11-12T10:00:00',
};

const SAMPLE_SHARE: CachedShareLink = {
  paymentLinkId: 25,
  invoiceId: 10,
  paymentUrl: 'http://localhost:5173/pay/ValidRawToken123456789012345678901234',
  rawToken: 'ValidRawToken123456789012345678901234',
  tokenPrefix: 'ppl_ValidR...',
  expiresAt: '2026-11-26T12:00:00',
};

describe('SendInvoiceEmailModal (Phase 4 Email Delivery)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loads server preview, validates email format, displays unconfigured SMTP warning, and sends email', async () => {
    vi.mocked(emailApi.previewInvoiceEmail).mockResolvedValue({
      recipientEmail: 'taylor@client.test',
      recipientName: 'Taylor Nguyen',
      subject: 'Invoice INV-20261112-EM01 from Apex Studio — 750.00 USD',
      htmlBody: '<html><body><h1>Invoice</h1></body></html>',
      textBody: 'Hello Taylor Nguyen, review your invoice at http://localhost:5173/pay/...',
      paymentUrl: SAMPLE_SHARE.paymentUrl,
      linkExpiresAt: '2026-11-26T12:00:00',
      mailConfigured: false,
    });
    vi.mocked(emailApi.sendInvoiceEmail).mockResolvedValue({
      deliveryId: 77,
      invoiceId: 10,
      recipientEmail: 'taylor@client.test',
      subject: 'Invoice INV-20261112-EM01 from Apex Studio — 750.00 USD',
      tokenPrefix: 'ppl_ValidR...',
      sentAt: '2026-11-12T10:20:00',
      sandboxMode: true,
    });

    const onClose = vi.fn();
    const onEmailSent = vi.fn();

    render(
      <SendInvoiceEmailModal
        open={true}
        invoice={SAMPLE_INVOICE}
        shareLink={SAMPLE_SHARE}
        onClose={onClose}
        onEmailSent={onEmailSent}
      />
    );

    expect(
      await screen.findByText(/Invoice INV-20261112-EM01 from Apex Studio — 750.00 USD/)
    ).toBeInTheDocument();
    expect(screen.getByText(/SMTP email transport is currently disabled/i)).toBeInTheDocument();

    // Switch preview tab to Plain Text cleanly
    fireEvent.click(screen.getByRole('button', { name: 'Plain Text' }));
    expect(screen.getByText(/Hello Taylor Nguyen, review your invoice/i)).toBeInTheDocument();

    // Invalid recipient email blocked before calling backend
    const recipientInput = screen.getByLabelText(/Recipient email/i);
    fireEvent.change(recipientInput, { target: { value: 'invalid-email' } });
    fireEvent.click(screen.getByRole('button', { name: /Send Invoice Email/i }));
    expect(await screen.findByText(/Enter a valid email address/i)).toBeInTheDocument();
    expect(emailApi.sendInvoiceEmail).not.toHaveBeenCalled();

    // Valid email triggers send cleanly
    fireEvent.change(recipientInput, { target: { value: 'taylor@client.test' } });
    fireEvent.change(screen.getByLabelText(/Personal note/i), {
      target: { value: 'Looking forward to next phase!' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Send Invoice Email/i }));

    await waitFor(() => {
      expect(emailApi.sendInvoiceEmail).toHaveBeenCalledWith(10, {
        rawToken: SAMPLE_SHARE.rawToken,
        recipientEmail: 'taylor@client.test',
        customMessage: 'Looking forward to next phase!',
      });
      expect(onEmailSent).toHaveBeenCalledTimes(1);
    });
  });

  it('surfaces backend SMTP or cooldown errors honestly without claiming sent', async () => {
    vi.mocked(emailApi.previewInvoiceEmail).mockResolvedValue({
      recipientEmail: 'taylor@client.test',
      recipientName: 'Taylor Nguyen',
      subject: 'Invoice INV-20261112-EM01',
      htmlBody: '<html><body>Hi</body></html>',
      textBody: 'Hi',
      paymentUrl: SAMPLE_SHARE.paymentUrl,
      linkExpiresAt: '2026-11-26T12:00:00',
      mailConfigured: true,
    });
    vi.mocked(emailApi.sendInvoiceEmail).mockRejectedValue(
      new ApiClientError({
        status: 409,
        error: 'Conflict',
        message: 'An invoice email was already sent recently.',
      })
    );

    const onEmailSent = vi.fn();

    render(
      <SendInvoiceEmailModal
        open={true}
        invoice={SAMPLE_INVOICE}
        shareLink={SAMPLE_SHARE}
        onClose={vi.fn()}
        onEmailSent={onEmailSent}
      />
    );

    await screen.findByText(/Invoice INV-20261112-EM01/);
    fireEvent.click(screen.getByRole('button', { name: /Send Invoice Email/i }));

    expect(
      await screen.findByText(/An invoice email was already sent recently/i)
    ).toBeInTheDocument();
    expect(onEmailSent).not.toHaveBeenCalled();
  });
});

