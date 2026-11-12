import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { AuthShell } from '@/components/auth/AuthShell';
import { resolvePostLoginRedirect } from '@/components/auth/ProtectedRoute';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { FormField } from '@/components/ui/FormField';
import { PasswordInput } from '@/components/ui/PasswordInput';
import { useAuth } from '@/hooks/useAuth';
import { ApiClientError } from '@/types';
import { validateLoginFields, type LoginFormErrors } from '@/utils/validation';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<LoginFormErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const redirectTarget = resolvePostLoginRedirect(location.state, '/');

  const clearFieldError = (field: keyof LoginFormErrors) => {
    setFieldErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev));
    if (submitError) {
      setSubmitError(null);
    }
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isSubmitting) {
      return;
    }

    const validationErrors = validateLoginFields(email, password);
    if (Object.keys(validationErrors).length > 0) {
      setFieldErrors(validationErrors);
      return;
    }

    setFieldErrors({});
    setSubmitError(null);
    setIsSubmitting(true);

    try {
      await login({ email: email.trim(), password });
      navigate(redirectTarget, { replace: true });
    } catch (error) {
      if (error instanceof ApiClientError) {
        setSubmitError(error.message);
        if (Object.keys(error.fieldErrors).length > 0) {
          setFieldErrors({
            email: error.fieldErrors.email,
            password: error.fieldErrors.password,
          });
        }
      } else {
        setSubmitError('Unable to sign in right now. Please try again.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <AuthShell
      badgeLabel="Account Sign In"
      title="Sign in to PayPilot AI"
      subtitle="Access your invoices, AI extraction drafts, and PayPal Sandbox workflows."
      footer={
        <>
          Need an account?{' '}
          <Link
            to="/register"
            className="font-semibold text-primary-600 hover:text-primary-700 focus:outline-none focus:underline"
          >
            Create an account
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        {submitError ? (
          <AlertBanner tone="error" title="Sign in failed" message={submitError} />
        ) : null}

        <FormField
          id="login-email"
          label="Email address"
          type="email"
          name="email"
          autoComplete="email"
          placeholder="you@company.com"
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
            clearFieldError('email');
          }}
          error={fieldErrors.email}
          disabled={isSubmitting}
          required
        />

        <PasswordInput
          id="login-password"
          label="Password"
          name="password"
          autoComplete="current-password"
          placeholder="Enter your password"
          value={password}
          onChange={(e) => {
            setPassword(e.target.value);
            clearFieldError('password');
          }}
          error={fieldErrors.password}
          disabled={isSubmitting}
          required
        />

        <button
          type="submit"
          disabled={isSubmitting}
          className="mt-2 inline-flex w-full items-center justify-center rounded-lg bg-primary-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {isSubmitting ? 'Signing in...' : 'Sign in'}
        </button>
      </form>
    </AuthShell>
  );
}

