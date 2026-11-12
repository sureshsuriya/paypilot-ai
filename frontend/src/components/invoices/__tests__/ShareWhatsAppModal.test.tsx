import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { customersApi } from '@/api';
import { ShareWhatsAppModal } from '@/components/invoices/ShareWhatsAppModal';
import type { CachedShareLink, InvoiceResponse } from '@/types';
import { buildWhatsAppShareUrl, formatWhatsAppInvoiceMessage } from '@/utils/whatsapp';

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api')>();
  return {
    ...actual,
    customersApi: {
      listCustomers: vi.fn(),
      getCustomer: vi.fn(),
      createCustomer: vi.fn(),
      updateCustomer: vi.fn(),
    },
  };
});

const SAMPLE_INVOICE: InvoiceResponse = {
  id: 14,
  customerId: 9,
  invoiceNumber: 'INV-20261112-WA01',
  paypalInvoiceId: null,
  clientName: 'Jordan Lee',
  clientEmail: 'jordan@client.test',
  clientPhone: '+14155552671',
  description: 'PayPilot UI milestone',
  amount: 750,
  currency: 'USD',
  status: 'APPROVED',
  dueDate: '2026-11-30',
  issuedAt: '2026-11-12',
  paidAt: null,
  aiExtracted: false,
  aiConfidence: null,
  snapshotHash: 'a'.repeat(64),
  approvedAt: '2026-11-12T10:00:00',
  approvedByUserId: 1,
  approvedSnapshotHash: 'a'.repeat(64),
  createdAt: '2026-11-12T09:55:00',
  updatedAt: '2026-11-12T10:00:00',
};

const SAMPLE_SHARE: CachedShareLink = {
  paymentLinkId: 40,
  invoiceId: 14,
  paymentUrl: 'http://localhost:5173/pay/WhatsAppShareToken123456789012345678',
  rawToken: 'WhatsAppShareToken123456789012345678',
  tokenPrefix: 'ppl_WhatsA...',
  expiresAt: '2026-11-26T12:00:00',
};

describe('Phase 5 WhatsApp Sharing', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    });
    vi.spyOn(window, 'open').mockImplementation(() => null);
  });

  it('formats deterministic WhatsApp message and constructs valid wa.me click-to-chat URLs', () => {
    const msg = formatWhatsAppInvoiceMessage(SAMPLE_INVOICE, SAMPLE_SHARE.paymentUrl);
    expect(msg).toContain('Hello Jordan Lee,');
    expect(msg).toContain('Your invoice INV-20261112-WA01 is ready.');
    expect(msg).toContain('Amount: $750.00 USD');
    expect(msg).toContain('Description: PayPilot UI milestone');
    expect(msg).toContain('Due date: Nov 30, 2026');
    expect(msg).toContain(SAMPLE_SHARE.paymentUrl);
    expect(msg).toContain('PayPilot AI');

    const bad = buildWhatsAppShareUrl('4155552671', msg);
    expect(bad.error).toBeTruthy();
    expect(bad.url).toBeUndefined();

    const good = buildWhatsAppShareUrl('+1 (415) 555-2671', msg);
    expect(good.error).toBeUndefined();
    expect(good.normalizedPhone).toBe('+14155552671');
    expect(good.url).toMatch(/^https:\/\/wa\.me\/14155552671\?text=/);
  });

  it('validates phone numbers, copies formatted text, opens wa.me cleanly, and never auto-marks sent', async () => {
    render(
      <ShareWhatsAppModal
        open={true}
        invoice={SAMPLE_INVOICE}
        shareLink={SAMPLE_SHARE}
        onClose={vi.fn()}
      />
    );

    expect(
      screen.getByText(/PayPilot AI never auto-sends messages/i)
    ).toBeInTheDocument();

    const phoneInput = screen.getByLabelText(/Customer WhatsApp phone number/i);

    // Invalid phone missing + blocks window.open
    fireEvent.change(phoneInput, { target: { value: '4155552671' } });
    fireEvent.click(screen.getByRole('button', { name: /Open WhatsApp to Send/i }));
    expect(await screen.findByText(/Include country code starting with \+/i)).toBeInTheDocument();
    expect(window.open).not.toHaveBeenCalled();

    // Copy message button copies exact formatted text
    fireEvent.click(screen.getByRole('button', { name: /Copy Message/i }));
    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(
        expect.stringContaining(SAMPLE_SHARE.paymentUrl)
      );
    });

    // Valid international phone opens wa.me with noopener,noreferrer
    fireEvent.change(phoneInput, { target: { value: '+91 98765 43210' } });
    fireEvent.click(screen.getByRole('button', { name: /Open WhatsApp to Send/i }));
    expect(window.open).toHaveBeenCalledWith(
      expect.stringMatching(/^https:\/\/wa\.me\/919876543210\?text=/),
      '_blank',
      'noopener,noreferrer'
    );
  });

  it('allows saving a missing customer phone number directly without invalidating approval', async () => {
    const invoiceMissingPhone = { ...SAMPLE_INVOICE, clientPhone: null };
    vi.mocked(customersApi.getCustomer).mockResolvedValue({
      customer: {
        id: 9,
        name: 'Jordan Lee',
        email: 'jordan@client.test',
        phone: null,
        company: null,
        notes: null,
        invoiceCount: 1,
        createdAt: '2026-11-12T09:50:00',
        updatedAt: '2026-11-12T09:50:00',
      },
      invoices: [],
    });
    vi.mocked(customersApi.updateCustomer).mockResolvedValue({
      id: 9,
      name: 'Jordan Lee',
      email: 'jordan@client.test',
      phone: '+14155552671',
      company: null,
      notes: null,
      invoiceCount: 1,
      createdAt: '2026-11-12T09:50:00',
      updatedAt: '2026-11-12T10:15:00',
    });

    const onPhoneSaved = vi.fn();

    render(
      <ShareWhatsAppModal
        open={true}
        invoice={invoiceMissingPhone}
        shareLink={SAMPLE_SHARE}
        onClose={vi.fn()}
        onPhoneSaved={onPhoneSaved}
      />
    );

    const phoneInput = screen.getByLabelText(/Customer WhatsApp phone number/i);
    fireEvent.change(phoneInput, { target: { value: '+1 (415) 555-2671' } });
    fireEvent.click(screen.getByRole('button', { name: /Save Phone to Customer Record/i }));

    await waitFor(() => {
      expect(customersApi.updateCustomer).toHaveBeenCalledWith(9, {
        name: 'Jordan Lee',
        email: 'jordan@client.test',
        phone: '+14155552671',
        company: null,
        notes: null,
      });
      expect(onPhoneSaved).toHaveBeenCalledTimes(1);
    });
  });
});

