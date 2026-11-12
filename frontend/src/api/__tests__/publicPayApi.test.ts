import { describe, expect, it, vi } from 'vitest';
import { publicApiClient, publicPayApi } from '@/api';
import type { PublicPaymentInvoiceResponse } from '@/types';

const SAMPLE_RESPONSE: PublicPaymentInvoiceResponse = {
  linkState: 'ACTIVE',
  payable: true,
  sandboxMode: true,
  statusMessage: 'Ready',
  expiresAt: '2026-11-30T12:00:00',
  invoiceNumber: 'INV-DEDUPE-1',
  sellerDisplayName: 'Apex Studio',
  customerName: 'Alex Mercer',
  description: 'Milestone 1',
  amount: 750,
  currency: 'USD',
  dueDate: '2026-11-28',
  invoiceStatus: 'APPROVED',
  activePayment: null,
  receipt: null,
};

describe('publicPayApi view tracking deduplication', () => {
  it('coalesces overlapping tracked GET calls and forwards trackView=false on refreshes', async () => {
    const adapter = vi.fn(async (config) => {
      await new Promise((r) => setTimeout(r, 15));
      return {
        data: SAMPLE_RESPONSE,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    });

    const originalAdapter = publicApiClient.defaults.adapter;
    publicApiClient.defaults.adapter = adapter;

    try {
      const token = 'dedupeToken1234567890123456789012345';

      // Two overlapping tracked GET requests resolve through a single HTTP call
      const [first, second] = await Promise.all([
        publicPayApi.getPublicInvoice(token, { trackView: true }),
        publicPayApi.getPublicInvoice(token, { trackView: true }),
      ]);
      expect(first.invoiceNumber).toBe('INV-DEDUPE-1');
      expect(second.invoiceNumber).toBe('INV-DEDUPE-1');
      expect(adapter).toHaveBeenCalledTimes(1);
      expect(adapter.mock.calls[0][0].params).toBeUndefined();

      // Explicit non-tracked refresh forwards ?trackView=false
      await publicPayApi.getPublicInvoice(token, { trackView: false });
      expect(adapter).toHaveBeenCalledTimes(2);
      expect(adapter.mock.calls[1][0].params).toEqual({ trackView: false });

      // Subsequent separate visit after settlement triggers a fresh tracked GET
      await publicPayApi.getPublicInvoice(token, { trackView: true });
      expect(adapter).toHaveBeenCalledTimes(3);
      expect(adapter.mock.calls[2][0].params).toBeUndefined();
    } finally {
      publicApiClient.defaults.adapter = originalAdapter;
    }
  });
});

