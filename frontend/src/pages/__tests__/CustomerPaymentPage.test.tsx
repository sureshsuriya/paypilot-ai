import { StrictMode } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { publicApiClient, publicPayApi } from '@/api';
import { CustomerPaymentPage } from '@/pages/CustomerPaymentPage';
import { ApiClientError, type PublicPaymentInvoiceResponse } from '@/types';

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api')>();
  return {
    ...actual,
    publicPayApi: {
      getPublicInvoice: vi.fn(),
      createOrder: vi.fn(),
      captureOrder: vi.fn(),
      cancelOrder: vi.fn(),
    },
  };
});

function renderAtPayRoute(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/pay/:token" element={<CustomerPaymentPage />} />
      </Routes>
    </MemoryRouter>
  );
}

function makeActiveInvoice(
  overrides: Partial<PublicPaymentInvoiceResponse> = {}
): PublicPaymentInvoiceResponse {
  return {
    linkState: 'ACTIVE',
    payable: true,
    sandboxMode: true,
    statusMessage: 'Invoice verified and ready for secure PayPal Sandbox payment.',
    expiresAt: '2026-11-25T12:00:00',
    invoiceNumber: 'INV-20261111-PUB01',
    sellerDisplayName: 'Northstar Digital Studio',
    customerName: 'Alex Mercer',
    description: 'Website development milestone',
    amount: 750,
    currency: 'USD',
    dueDate: '2026-11-20',
    invoiceStatus: 'APPROVED',
    activePayment: null,
    receipt: null,
    ...overrides,
  };
}

function makePaidInvoice(
  overrides: Partial<PublicPaymentInvoiceResponse> = {}
): PublicPaymentInvoiceResponse {
  return {
    linkState: 'PAID',
    payable: false,
    sandboxMode: true,
    statusMessage: 'Payment verified and completed in PayPal Sandbox.',
    expiresAt: '2026-11-25T12:00:00',
    invoiceNumber: 'INV-20261111-PUB01',
    sellerDisplayName: 'Northstar Digital Studio',
    customerName: 'Alex Mercer',
    description: 'Website development milestone',
    amount: 750,
    currency: 'USD',
    dueDate: '2026-11-20',
    invoiceStatus: 'PAID',
    activePayment: {
      paypalOrderId: 'ORD-CUST-750',
      status: 'CAPTURED',
      pendingReason: null,
      approvalUrl: null,
      updatedAt: '2026-11-11T17:15:00',
    },
    receipt: {
      invoiceNumber: 'INV-20261111-PUB01',
      sellerDisplayName: 'Northstar Digital Studio',
      customerName: 'Alex Mercer',
      description: 'Website development milestone',
      amount: 750,
      currency: 'USD',
      paypalOrderId: 'ORD-CUST-750',
      paypalCaptureId: 'CAP-CUST-750',
      paymentStatus: 'CAPTURED',
      paidAt: '2026-11-11T17:15:00',
      sandboxMode: true,
    },
    ...overrides,
  };
}

describe('CustomerPaymentPage (Customer Portal)', () => {
  const assignSpy = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: {
        ...window.location,
        origin: 'http://localhost:5173',
        assign: assignSpy,
      },
    });
  });

  it('never attaches seller JWT headers via publicApiClient even when logged in as seller', async () => {
    window.localStorage.setItem('paypilot_access_token', 'seller-secret-jwt-should-not-leak');
    const adapter = vi.fn(async (config) => ({
      data: makeActiveInvoice(),
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }));

    const originalAdapter = publicApiClient.defaults.adapter;
    publicApiClient.defaults.adapter = adapter;
    try {
      await publicApiClient.get('/public/pay/sample-token');
      expect(adapter).toHaveBeenCalledTimes(1);
      const sentHeaders = adapter.mock.calls[0][0].headers;
      expect(sentHeaders?.Authorization).toBeUndefined();
      expect(sentHeaders?.authorization).toBeUndefined();
    } finally {
      publicApiClient.defaults.adapter = originalAdapter;
    }
  });

  it('renders invoice details cleanly with zero seller portal navigation links and initiates Sandbox checkout', async () => {
    vi.mocked(publicPayApi.getPublicInvoice).mockResolvedValue(makeActiveInvoice());
    vi.mocked(publicPayApi.createOrder).mockResolvedValue({
      paypalOrderId: 'ORD-SANDBOX-1',
      status: 'CREATED',
      amount: 750,
      currency: 'USD',
      approvalUrl: 'https://www.sandbox.paypal.com/checkoutnow?token=ORD-SANDBOX-1',
      reused: false,
      sandboxMode: true,
    });

    renderAtPayRoute('/pay/validToken12345678901234567890123456');

    expect(await screen.findByText('INV-20261111-PUB01')).toBeInTheDocument();
    expect(screen.getByText(/Northstar Digital Studio/i)).toBeInTheDocument();
    expect(screen.getByText('Alex Mercer')).toBeInTheDocument();
    expect(screen.getByText('Website development milestone')).toBeInTheDocument();
    expect(screen.getByText('$750.00 USD')).toBeInTheDocument();
    expect(screen.getByText(/PayPal Sandbox Mode/i)).toBeInTheDocument();

    // Separate customer portal must contain zero Seller Portal nav links
    expect(screen.queryByRole('link', { name: /dashboard/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /invoices/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /customers/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /sign in/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Pay with PayPal \(Sandbox\)/i }));

    await waitFor(() => {
      expect(publicPayApi.createOrder).toHaveBeenCalledWith(
        'validToken12345678901234567890123456',
        {
          returnUrl: 'http://localhost:5173/pay/validToken12345678901234567890123456?paypal=return',
          cancelUrl: 'http://localhost:5173/pay/validToken12345678901234567890123456?paypal=cancel',
        }
      );
      expect(assignSpy).toHaveBeenCalledWith(
        'https://www.sandbox.paypal.com/checkoutnow?token=ORD-SANDBOX-1'
      );
    });
  });

  it('rejects untrusted non-sandbox PayPal approval URLs before redirecting', async () => {
    vi.mocked(publicPayApi.getPublicInvoice).mockResolvedValue(makeActiveInvoice());
    vi.mocked(publicPayApi.createOrder).mockResolvedValue({
      paypalOrderId: 'ORD-EVIL-1',
      status: 'CREATED',
      amount: 750,
      currency: 'USD',
      approvalUrl: 'https://www.paypal.com/checkoutnow?token=LIVE-DISALLOWED',
      reused: false,
      sandboxMode: true,
    });

    renderAtPayRoute('/pay/tokenBadRedirect12345678901234567890');

    await screen.findByText('INV-20261111-PUB01');
    fireEvent.click(screen.getByRole('button', { name: /Pay with PayPal/i }));

    expect(await screen.findByText(/Invalid PayPal Sandbox redirect URL/i)).toBeInTheDocument();
    expect(assignSpy).not.toHaveBeenCalled();
  });

  it('verifies PayPal return capture with backend before displaying verified PAID receipt', async () => {
    vi.mocked(publicPayApi.getPublicInvoice).mockResolvedValue(makeActiveInvoice());
    vi.mocked(publicPayApi.captureOrder).mockResolvedValue(makePaidInvoice());

    renderAtPayRoute('/pay/returnToken1234567890123456789012345?paypal=return&token=ORD-CUST-750');

    await waitFor(() => {
      expect(publicPayApi.captureOrder).toHaveBeenCalledWith(
        'returnToken1234567890123456789012345',
        { orderId: 'ORD-CUST-750' }
      );
    });

    expect(await screen.findByRole('heading', { name: /Payment Confirmed/i })).toBeInTheDocument();
    expect(screen.getByText('CAP-CUST-750')).toBeInTheDocument();
    expect(screen.getByText('ORD-CUST-750')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Print Receipt/i })).toBeInTheDocument();
  });

  it('handles PENDING capture cleanly without claiming PAID and lets customer recheck status', async () => {
    const pendingState = makeActiveInvoice({
      payable: false,
      activePayment: {
        paypalOrderId: 'ORD-PEND-99',
        status: 'PENDING',
        pendingReason: 'PENDING_REVIEW',
        approvalUrl: null,
        updatedAt: '2026-11-11T17:10:00',
      },
    });
    vi.mocked(publicPayApi.getPublicInvoice).mockResolvedValue(pendingState);
    vi.mocked(publicPayApi.captureOrder).mockResolvedValue(makePaidInvoice({
      activePayment: {
        paypalOrderId: 'ORD-PEND-99',
        status: 'CAPTURED',
        pendingReason: null,
        approvalUrl: null,
        updatedAt: '2026-11-11T17:12:00',
      },
      receipt: {
        ...makePaidInvoice().receipt!,
        paypalOrderId: 'ORD-PEND-99',
        paypalCaptureId: 'CAP-SETTLED-99',
      },
    }));

    renderAtPayRoute('/pay/pendingToken123456789012345678901234');

    expect(await screen.findByText(/Payment awaiting PayPal verification/i)).toBeInTheDocument();
    expect(screen.getByText(/PENDING_REVIEW/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /Payment Confirmed/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Check Payment Status/i }));

    expect(await screen.findByRole('heading', { name: /Payment Confirmed/i })).toBeInTheDocument();
    expect(screen.getByText('CAP-SETTLED-99')).toBeInTheDocument();
  });

  it('handles PayPal cancel return cleanly and preserves retry option', async () => {
    vi.mocked(publicPayApi.getPublicInvoice).mockResolvedValue(makeActiveInvoice());
    vi.mocked(publicPayApi.cancelOrder).mockResolvedValue(makeActiveInvoice());

    renderAtPayRoute('/pay/cancelToken1234567890123456789012345?paypal=cancel&token=ORD-CAN-1');

    await waitFor(() => {
      expect(publicPayApi.cancelOrder).toHaveBeenCalledWith(
        'cancelToken1234567890123456789012345',
        {
          orderId: 'ORD-CAN-1',
          reason: 'Checkout cancelled by customer on PayPal Sandbox',
        }
      );
    });

    expect(await screen.findByText(/PayPal checkout cancelled/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Pay with PayPal \(Sandbox\)/i })).toBeEnabled();
  });

  it('renders clear REVOKED, EXPIRED, and 404 not-found screens with zero invoice leaks', async () => {
    vi.mocked(publicPayApi.getPublicInvoice).mockResolvedValueOnce({
      linkState: 'REVOKED',
      payable: false,
      sandboxMode: true,
      statusMessage: 'This payment link has been revoked by the seller.',
      expiresAt: null,
      invoiceNumber: null,
      sellerDisplayName: null,
      customerName: null,
      description: null,
      amount: null,
      currency: null,
      dueDate: null,
      invoiceStatus: null,
      activePayment: null,
      receipt: null,
    });

    renderAtPayRoute('/pay/revokedToken12345678901234567890123');
    expect(await screen.findByText(/Payment link revoked/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Pay with PayPal/i })).not.toBeInTheDocument();

    vi.mocked(publicPayApi.getPublicInvoice).mockResolvedValueOnce({
      linkState: 'EXPIRED',
      payable: false,
      sandboxMode: true,
      statusMessage: 'This payment link has expired.',
      expiresAt: '2026-11-01T12:00:00',
      invoiceNumber: null,
      sellerDisplayName: null,
      customerName: null,
      description: null,
      amount: null,
      currency: null,
      dueDate: null,
      invoiceStatus: null,
      activePayment: null,
      receipt: null,
    });

    renderAtPayRoute('/pay/expiredToken12345678901234567890123');
    expect(await screen.findByText(/Payment link expired/i)).toBeInTheDocument();

    vi.mocked(publicPayApi.getPublicInvoice).mockRejectedValueOnce(
      new ApiClientError({
        status: 404,
        error: 'Not Found',
        message: 'Payment link not found.',
      })
    );

    renderAtPayRoute('/pay/unknownToken12345678901234567890123');
    expect(await screen.findByText(/Payment link not found/i)).toBeInTheDocument();
  });

  it('tracks exactly one view under React StrictMode and passes trackView=false on Refresh and PayPal returns', async () => {
    const token = 'strictModeToken1234567890123456789012';
    vi.mocked(publicPayApi.getPublicInvoice).mockResolvedValue(makeActiveInvoice());

    render(
      <StrictMode>
        <MemoryRouter initialEntries={[`/pay/${token}`]}>
          <Routes>
            <Route path="/pay/:token" element={<CustomerPaymentPage />} />
          </Routes>
        </MemoryRouter>
      </StrictMode>
    );

    expect(await screen.findByText('INV-20261111-PUB01')).toBeInTheDocument();
    expect(publicPayApi.getPublicInvoice).toHaveBeenCalledTimes(1);
    expect(publicPayApi.getPublicInvoice).toHaveBeenCalledWith(token, { trackView: true });

    // Manual Refresh uses trackView=false so Customer Views does not inflate
    fireEvent.click(screen.getByRole('button', { name: /Refresh/i }));
    await waitFor(() => {
      expect(publicPayApi.getPublicInvoice).toHaveBeenCalledTimes(2);
    });
    expect(publicPayApi.getPublicInvoice).toHaveBeenLastCalledWith(token, { trackView: false });

    // Returning from PayPal Checkout (?paypal=return) never passes trackView=true
    vi.mocked(publicPayApi.getPublicInvoice).mockClear();
    vi.mocked(publicPayApi.captureOrder).mockResolvedValue(makePaidInvoice());
    const returnToken = 'returnCallbackOnly1234567890123456789';

    render(
      <StrictMode>
        <MemoryRouter initialEntries={[`/pay/${returnToken}?paypal=return&token=ORD-RET-9`]}>
          <Routes>
            <Route path="/pay/:token" element={<CustomerPaymentPage />} />
          </Routes>
        </MemoryRouter>
      </StrictMode>
    );

    expect(await screen.findByRole('heading', { name: /Payment Confirmed/i })).toBeInTheDocument();
    for (const call of vi.mocked(publicPayApi.getPublicInvoice).mock.calls) {
      expect(call[1]?.trackView).toBe(false);
    }
  });
});
