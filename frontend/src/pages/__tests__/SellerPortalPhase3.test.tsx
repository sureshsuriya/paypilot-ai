import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { customersApi, invoicesApi, paymentsApi } from '@/api';
import { PaymentLinkManagerPanel } from '@/components/invoices/PaymentLinkManagerPanel';
import { AuthContext, type AuthContextValue } from '@/context/AuthContext';
import { CustomersPage } from '@/pages/CustomersPage';
import { DashboardPage } from '@/pages/DashboardPage';
import { PaymentsPage } from '@/pages/PaymentsPage';
import type {
  CachedShareLink,
  CustomerResponse,
  InvoiceResponse,
  PaymentLinkSummaryResponse,
  PaymentResponse,
} from '@/types';

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api')>();
  return {
    ...actual,
    invoicesApi: {
      listInvoices: vi.fn(),
      getInvoice: vi.fn(),
      createInvoice: vi.fn(),
      updateInvoice: vi.fn(),
      approveInvoice: vi.fn(),
      revokeApproval: vi.fn(),
      deleteDraftInvoice: vi.fn(),
    },
    customersApi: {
      listCustomers: vi.fn(),
      getCustomer: vi.fn(),
      createCustomer: vi.fn(),
      updateCustomer: vi.fn(),
    },
    paymentsApi: {
      listAllPayments: vi.fn(),
      createOrder: vi.fn(),
      captureOrder: vi.fn(),
      cancelOrder: vi.fn(),
      getPaymentHistory: vi.fn(),
    },
  };
});

const SELLER_AUTH_VALUE: AuthContextValue = {
  user: { id: 1, name: 'Apex Studio', email: 'apex@seller.test', role: 'USER' },
  token: 'seller-test-jwt',
  expiresAtEpochMs: Date.now() + 3600_000,
  isAuthenticated: true,
  isLoading: false,
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
};

function makeInvoice(overrides: Partial<InvoiceResponse> = {}): InvoiceResponse {
  return {
    id: 10,
    customerId: 5,
    invoiceNumber: 'INV-20261112-001',
    paypalInvoiceId: null,
    clientName: 'Alex Mercer',
    clientEmail: 'alex@client.test',
    clientPhone: '+14155552671',
    description: 'Platform architecture sprint',
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
    ...overrides,
  };
}

function makePayment(overrides: Partial<PaymentResponse> = {}): PaymentResponse {
  return {
    id: 100,
    invoiceId: 10,
    invoiceNumber: 'INV-20261112-001',
    clientName: 'Alex Mercer',
    invoiceStatus: 'PAID',
    paymentLinkId: 33,
    customerLinkCheckout: true,
    paypalOrderId: 'ORD-CUST-100',
    paypalCaptureId: 'CAP-CUST-100',
    status: 'CAPTURED',
    amount: 750,
    currency: 'USD',
    approvalUrl: null,
    invoiceSnapshotHash: 'a'.repeat(64),
    pendingReason: null,
    completed: true,
    pending: false,
    locallyCancelled: false,
    paypalCancellationConfirmed: false,
    reused: false,
    capturedAt: '2026-11-12T10:15:00',
    createdAt: '2026-11-12T10:10:00',
    updatedAt: '2026-11-12T10:15:00',
    summary: {},
    ...overrides,
  };
}

describe('Phase 3 Seller Portal Features', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: vi.fn().mockResolvedValue(undefined),
      },
    });
  });

  it('renders DashboardPage metrics including Pending Payments and Revenue Overview', async () => {
    vi.mocked(invoicesApi.listInvoices).mockResolvedValue([
      makeInvoice({ id: 1, status: 'PAID', amount: 750 }),
      makeInvoice({ id: 2, invoiceNumber: 'INV-20261112-002', status: 'APPROVED', amount: 300 }),
      makeInvoice({ id: 3, invoiceNumber: 'INV-20261112-003', status: 'DRAFT', amount: 120 }),
    ]);
    vi.mocked(paymentsApi.listAllPayments).mockResolvedValue([
      makePayment({ id: 1, invoiceId: 1, status: 'CAPTURED', amount: 750, customerLinkCheckout: true }),
      makePayment({
        id: 2,
        invoiceId: 2,
        invoiceNumber: 'INV-20261112-002',
        status: 'PENDING',
        amount: 300,
        completed: false,
        pending: true,
        paypalCaptureId: 'CAP-PEND-2',
      }),
    ]);

    render(
      <AuthContext.Provider value={SELLER_AUTH_VALUE}>
        <MemoryRouter initialEntries={['/dashboard']}>
          <Routes>
            <Route path="/dashboard" element={<DashboardPage />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    );

    expect(await screen.findByText('Pending Payments')).toBeInTheDocument();
    const revenueSection = screen.getByLabelText('Revenue Overview');
    expect(within(revenueSection).getByText('$750.00 USD')).toBeInTheDocument();
    expect(within(revenueSection).getByText('$420.00 USD')).toBeInTheDocument();
    expect(within(revenueSection).getByText(/1 via Customer Portal links/i)).toBeInTheDocument();
    expect(screen.getByText(/1 awaiting capture settlement/i)).toBeInTheDocument();
  });

  it('lists customers, enforces E.164 phone validation, and updates missing phone numbers', async () => {
    const initialCustomer: CustomerResponse = {
      id: 7,
      name: 'Taylor Nguyen',
      email: 'taylor@north.test',
      phone: null,
      company: 'North Co',
      notes: null,
      invoiceCount: 2,
      createdAt: '2026-11-12T08:00:00',
      updatedAt: '2026-11-12T08:00:00',
    };
    vi.mocked(customersApi.listCustomers).mockResolvedValue([initialCustomer]);
    vi.mocked(customersApi.updateCustomer).mockResolvedValue({
      ...initialCustomer,
      phone: '+14155552671',
    });

    render(
      <AuthContext.Provider value={SELLER_AUTH_VALUE}>
        <MemoryRouter initialEntries={['/customers']}>
          <Routes>
            <Route path="/customers" element={<CustomersPage />} />
            <Route path="/invoices/create" element={<div>Create Invoice Route</div>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    );

    expect((await screen.findAllByText('Taylor Nguyen')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Missing phone/i).length).toBeGreaterThan(0);

    fireEvent.click(screen.getAllByRole('button', { name: 'Add Phone' })[0]);
    const phoneInput = screen.getByLabelText(/WhatsApp \/ Mobile Phone/i);

    // Reject missing + country code client-side
    fireEvent.change(phoneInput, { target: { value: '4155552671' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Customer' }));
    expect(await screen.findByText(/Include country code starting with \+/i)).toBeInTheDocument();
    expect(customersApi.updateCustomer).not.toHaveBeenCalled();

    // Valid international E.164 submission succeeds
    fireEvent.change(phoneInput, { target: { value: '+1 (415) 555-2671' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save Customer' }));

    await waitFor(() => {
      expect(customersApi.updateCustomer).toHaveBeenCalledWith(7, {
        name: 'Taylor Nguyen',
        email: 'taylor@north.test',
        phone: '+14155552671',
        company: 'North Co',
        notes: null,
      });
    });

    // Quick-create shortcut navigates cleanly
    fireEvent.click(screen.getAllByRole('button', { name: 'New Invoice' })[0]);
    expect(await screen.findByText('Create Invoice Route')).toBeInTheDocument();
  });

  it('manages single-active payment links with copy, regeneration confirmation, and revocation', async () => {
    const generateFn = vi.fn().mockResolvedValue(undefined);
    const revokeFn = vi.fn().mockResolvedValue(undefined);

    const activeLink: PaymentLinkSummaryResponse = {
      id: 41,
      invoiceId: 10,
      tokenPrefix: 'ppl_AbCdEf...',
      active: true,
      expired: false,
      revoked: false,
      expiresAt: '2026-11-26T12:00:00',
      revokedAt: null,
      revocationReason: null,
      accessCount: 3,
      lastAccessedAt: '2026-11-12T10:05:00',
      createdAt: '2026-11-12T09:55:00',
    };

    const cachedShare: CachedShareLink = {
      paymentLinkId: 41,
      invoiceId: 10,
      paymentUrl: 'http://localhost:5173/pay/SecretTokenValueForCustomerTest123456',
      rawToken: 'SecretTokenValueForCustomerTest123456',
      tokenPrefix: 'ppl_AbCdEf...',
      expiresAt: '2026-11-26T12:00:00',
    };

    render(
      <PaymentLinkManagerPanel
        invoice={makeInvoice()}
        links={[activeLink]}
        cachedShareLink={cachedShare}
        hasPendingOrCapturedPayment={false}
        isBusy={false}
        onGenerateOrRotate={generateFn}
        onRevokeActive={revokeFn}
      />
    );

    expect(screen.getByDisplayValue(cachedShare.paymentUrl)).toBeInTheDocument();
    expect(screen.getByText('3 visits')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Copy Link/i }));
    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(cachedShare.paymentUrl);
    });

    fireEvent.click(screen.getByRole('button', { name: /Regenerate Link/i }));
    expect(await screen.findByText(/Regenerate Customer Payment Link\?/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Revoke Old & Generate New/i }));
    await waitFor(() => {
      expect(generateFn).toHaveBeenCalledWith(14);
    });

    fireEvent.click(screen.getByRole('button', { name: /^Revoke Link$/i }));
    expect(await screen.findByText(/Revoke Active Payment Link\?/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Confirm Revoke Link/i }));
    await waitFor(() => {
      expect(revokeFn).toHaveBeenCalledWith('Revoked by seller');
    });
  });

  it('displays unified payments with Customer Link provenance and client filtering on PaymentsPage', async () => {
    vi.mocked(paymentsApi.listAllPayments).mockResolvedValue([
      makePayment({
        id: 1,
        invoiceNumber: 'INV-LINK-01',
        clientName: 'Link Buyer',
        customerLinkCheckout: true,
        status: 'CAPTURED',
      }),
      makePayment({
        id: 2,
        invoiceNumber: 'INV-CANCEL-02',
        clientName: 'Cancel Buyer',
        paypalOrderId: 'ORD-CANCEL-2',
        paypalCaptureId: null,
        customerLinkCheckout: false,
        status: 'CANCELLED',
        locallyCancelled: true,
        completed: false,
      }),
    ]);

    render(
      <MemoryRouter initialEntries={['/payments']}>
        <Routes>
          <Route path="/payments" element={<PaymentsPage />} />
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByText('INV-LINK-01')).toBeInTheDocument();
    expect(screen.getByText('Customer Link')).toBeInTheDocument();
    expect(screen.getByText('Seller Portal')).toBeInTheDocument();
    expect(screen.getByText(/Link Buyer/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'CANCELLED' }));
    expect(screen.queryByText('INV-LINK-01')).not.toBeInTheDocument();
    expect(screen.getByText('INV-CANCEL-02')).toBeInTheDocument();
  });
});

