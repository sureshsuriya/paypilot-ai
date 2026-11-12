import { Link } from 'react-router-dom';
import { useAuth } from '@/hooks/useAuth';

export function NotFoundPage() {
  const { isAuthenticated } = useAuth();

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 px-4 py-12">
      <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-sm">
        <p className="text-xs font-semibold uppercase tracking-widest text-primary-600">
          404 · Page Not Found
        </p>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight text-slate-900">
          We couldn&apos;t find that page
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          The route you requested does not exist or may have moved.
        </p>
        <div className="mt-6 flex justify-center">
          <Link
            to={isAuthenticated ? '/' : '/login'}
            className="rounded-lg bg-primary-600 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2"
          >
            {isAuthenticated ? 'Return to Dashboard' : 'Go to Sign In'}
          </Link>
        </div>
      </div>
    </div>
  );
}

