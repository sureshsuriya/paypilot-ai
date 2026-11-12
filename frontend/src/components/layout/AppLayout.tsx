import { useEffect, useState, type ComponentType, type SVGProps } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  CloseIcon,
  CreateInvoiceIcon,
  DashboardIcon,
  InvoiceIcon,
  LogOutIcon,
  MenuIcon,
  PayPilotMark,
  PaymentsIcon,
  UsersIcon,
} from '@/components/ui/Icons';
import { BackendHealthBadge } from './BackendHealthBadge';
import { SandboxIndicator } from './SandboxIndicator';
import { useAuth } from '@/hooks/useAuth';
import { useBackendHealth } from '@/hooks/useBackendHealth';

interface NavItem {
  label: string;
  to: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
}

const NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', to: '/dashboard', icon: DashboardIcon },
  { label: 'Invoices', to: '/invoices', icon: InvoiceIcon },
  { label: 'Create Invoice', to: '/invoices/create', icon: CreateInvoiceIcon },
  { label: 'Customers', to: '/customers', icon: UsersIcon },
  { label: 'Payments', to: '/payments', icon: PaymentsIcon },
];

function isNavItemActive(pathname: string, target: string): boolean {
  if (target === '/dashboard' || target === '/') {
    return pathname === '/' || pathname === '/dashboard';
  }
  if (target === '/invoices') {
    return pathname === '/invoices' || /^\/invoices\/\d+/.test(pathname);
  }
  if (target === '/invoices/create') {
    return pathname === '/invoices/create' || pathname === '/invoices/new';
  }
  return pathname === target || pathname.startsWith(`${target}/`);
}

function getPageTitle(pathname: string): string {
  if (pathname === '/' || pathname === '/dashboard') {
    return 'Dashboard';
  }
  if (pathname === '/invoices') {
    return 'Invoices';
  }
  if (pathname === '/invoices/create' || pathname === '/invoices/new') {
    return 'Create Invoice';
  }
  if (pathname.startsWith('/invoices/')) {
    return 'Invoice Preview & Payment Link';
  }
  if (pathname.startsWith('/customers')) {
    return 'Customers';
  }
  if (pathname.startsWith('/payments')) {
    return 'Payments';
  }
  return 'Seller Portal';
}

function getUserInitials(name?: string | null): string {
  if (!name || !name.trim()) {
    return 'U';
  }
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return `${parts[0][0] ?? ''}${parts[parts.length - 1][0] ?? ''}`.toUpperCase();
}

export function AppLayout() {
  const { user, logout } = useAuth();
  const health = useBackendHealth();
  const location = useLocation();
  const navigate = useNavigate();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  useEffect(() => {
    setMobileMenuOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    if (!mobileMenuOpen) {
      return undefined;
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMobileMenuOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [mobileMenuOpen]);

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const pageTitle = getPageTitle(location.pathname);

  const sidebarContent = (
    <div className="flex h-full flex-col justify-between bg-white px-4 py-5">
      <div>
        <Link
          to="/"
          className="flex items-center gap-3 rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          <PayPilotMark className="h-8 w-8 shrink-0" />
          <div>
            <span className="block text-sm font-semibold tracking-tight text-slate-900">
              PayPilot AI
            </span>
            <span className="block text-[11px] font-medium text-slate-500">
              Invoice &amp; Payment Suite
            </span>
          </div>
        </Link>

        <nav aria-label="Primary navigation" className="mt-7 space-y-1">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            const active = isNavItemActive(location.pathname, item.to);
            return (
              <Link
                key={item.to}
                to={item.to}
                aria-current={active ? 'page' : undefined}
                className={[
                  'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors duration-150',
                  'focus:outline-none focus:ring-2 focus:ring-primary-500',
                  active
                    ? 'bg-primary-50 text-primary-700'
                    : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
                ].join(' ')}
              >
                <Icon
                  className={`h-4 w-4 shrink-0 ${active ? 'text-primary-600' : 'text-slate-400'}`}
                />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </nav>
      </div>

      <div className="space-y-4">
        <SandboxIndicator variant="panel" />

        <div className="rounded-xl border border-slate-200 bg-slate-50/70 p-3">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-slate-900 text-xs font-semibold text-white">
              {getUserInitials(user?.name)}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-slate-900">
                {user?.name ?? 'Authenticated User'}
              </p>
              <p className="truncate text-xs text-slate-500">{user?.email}</p>
            </div>
          </div>

          <button
            type="button"
            onClick={handleLogout}
            className="mt-3 flex w-full items-center justify-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-medium text-slate-700 transition-colors hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <LogOutIcon className="h-3.5 w-3.5" />
            <span>Sign out</span>
          </button>
        </div>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      {/* Desktop sidebar */}
      <aside className="hidden lg:fixed lg:inset-y-0 lg:left-0 lg:z-30 lg:flex lg:w-64 lg:flex-col lg:border-r lg:border-slate-200">
        {sidebarContent}
      </aside>

      {/* Mobile slide-over navigation */}
      {mobileMenuOpen ? (
        <div className="fixed inset-0 z-40 flex lg:hidden" role="dialog" aria-modal="true" aria-label="Navigation menu">
          <div
            className="fixed inset-0 bg-slate-900/40 backdrop-blur-[1px]"
            onClick={() => setMobileMenuOpen(false)}
          />
          <div className="relative z-10 flex w-72 max-w-[85vw] flex-col border-r border-slate-200 bg-white shadow-xl">
            <button
              type="button"
              onClick={() => setMobileMenuOpen(false)}
              aria-label="Close navigation menu"
              className="absolute right-3 top-4 rounded-lg p-1.5 text-slate-500 hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <CloseIcon />
            </button>
            {sidebarContent}
          </div>
        </div>
      ) : null}

      {/* Main workspace column */}
      <div className="flex min-h-screen flex-col lg:pl-64">
        <header className="sticky top-0 z-20 border-b border-slate-200 bg-white/95 backdrop-blur">
          <div className="mx-auto flex h-16 max-w-7xl items-center justify-between gap-3 px-4 sm:px-6 lg:px-8">
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => setMobileMenuOpen(true)}
                aria-label="Open navigation menu"
                aria-expanded={mobileMenuOpen}
                className="rounded-lg p-2 text-slate-600 hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus:ring-2 focus:ring-primary-500 lg:hidden"
              >
                <MenuIcon />
              </button>
              <div>
                <h1 className="text-base font-semibold tracking-tight text-slate-900 sm:text-lg">
                  {pageTitle}
                </h1>
              </div>
            </div>

            <div className="flex items-center gap-2.5 sm:gap-3">
              <div className="hidden sm:block">
                <SandboxIndicator variant="compact" />
              </div>
              <BackendHealthBadge health={health} />
              <div className="hidden md:flex items-center gap-2 border-l border-slate-200 pl-3">
                <span className="text-xs font-medium text-slate-700">
                  {user?.name}
                </span>
                <button
                  type="button"
                  onClick={handleLogout}
                  className="rounded-lg px-2.5 py-1.5 text-xs font-medium text-slate-600 transition-colors hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus:ring-2 focus:ring-primary-500"
                >
                  Sign out
                </button>
              </div>
            </div>
          </div>

          <div className="border-t border-amber-200/70 bg-amber-50/70 px-4 py-1.5 text-center text-[11px] font-medium text-amber-900 sm:hidden">
            PayPal Sandbox Mode — Test environment only. No real money is charged.
          </div>
        </header>

        <main className="flex-1">
          <div className="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}

