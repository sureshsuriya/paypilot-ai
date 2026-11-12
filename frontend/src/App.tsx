import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { ProtectedRoute } from '@/components/auth/ProtectedRoute';
import { PublicOnlyRoute } from '@/components/auth/PublicOnlyRoute';
import { AppLayout } from '@/components/layout/AppLayout';
import { CreateInvoicePage } from '@/pages/CreateInvoicePage';
import { CustomerPaymentPage } from '@/pages/CustomerPaymentPage';
import { CustomersPage } from '@/pages/CustomersPage';
import { DashboardPage } from '@/pages/DashboardPage';
import { InvoiceDetailPage } from '@/pages/InvoiceDetailPage';
import { InvoicesPage } from '@/pages/InvoicesPage';
import { LoginPage } from '@/pages/LoginPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { PaymentsPage } from '@/pages/PaymentsPage';
import { RegisterPage } from '@/pages/RegisterPage';

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/pay/:token" element={<CustomerPaymentPage />} />

        <Route element={<PublicOnlyRoute />}>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
        </Route>

        <Route element={<ProtectedRoute />}>
          <Route element={<AppLayout />}>
            <Route path="/" element={<DashboardPage />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/invoices" element={<InvoicesPage />} />
            <Route path="/invoices/create" element={<CreateInvoicePage />} />
            <Route path="/invoices/new" element={<CreateInvoicePage />} />
            <Route path="/invoices/:id" element={<InvoiceDetailPage />} />
            <Route path="/customers" element={<CustomersPage />} />
            <Route path="/payments" element={<PaymentsPage />} />
          </Route>
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
