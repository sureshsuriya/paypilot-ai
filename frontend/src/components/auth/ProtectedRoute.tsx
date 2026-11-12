import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation, type Location } from 'react-router-dom';
import { useAuth } from '@/hooks/useAuth';

export interface ProtectedRouteProps {
  children?: ReactNode;
}

interface RedirectLocationState {
  from?: Partial<Location>;
}

/**
 * Resolves a safe in-app redirect target (preserving path, query parameters such as
 * PayPal return/cancel tokens, and hash) while preventing open redirects.
 */
export function resolvePostLoginRedirect(state: unknown, fallback = '/'): string {
  if (!state || typeof state !== 'object') {
    return fallback;
  }

  const from = (state as RedirectLocationState).from;
  if (!from || typeof from.pathname !== 'string') {
    return fallback;
  }

  const pathname = from.pathname;
  if (!pathname.startsWith('/') || pathname.startsWith('//')) {
    return fallback;
  }

  const search = typeof from.search === 'string' ? from.search : '';
  const hash = typeof from.hash === 'string' ? from.hash : '';
  const target = `${pathname}${search}${hash}`;

  return target.startsWith('/login') || target.startsWith('/register')
    ? fallback
    : target;
}

/**
 * Route guard redirecting unauthenticated requests to `/login` while preserving the
 * attempted location (including PayPal return query params) in router state.
 */
export function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50 text-sm text-gray-600">
        Verifying session...
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return children ? <>{children}</> : <Outlet />;
}

