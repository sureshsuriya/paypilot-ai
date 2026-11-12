import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AuthShell } from '@/components/auth/AuthShell';
import { AlertBanner } from '@/components/ui/AlertBanner';
import { FormField } from '@/components/ui/FormField';
import { PasswordInput } from '@/components/ui/PasswordInput';
import { useAuth } from '@/hooks/useAuth';
import { ApiClientError } from '@/types';
import { validateRegisterFields, type RegisterFormErrors } from '@/utils/validation';

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();

  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<RegisterFormErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const clearFieldError = (field: keyof RegisterFormErrors) => {
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

    const validationErrors = validateRegisterFields(name, email, password);
    if (Object.keys(validationErrors).length > 0) {
      setFieldErrors(validationErrors);
      return;
    }

    setFieldErrors({});
    setSubmitError(null);
    setIsSubmitting(true);

    try {
      await register({
        name: name.trim(),
        email: email.trim(),
        password,
      });
      navigate('/', { replace: true });
    } catch (error) {
      if (error instanceof ApiClientError) {
        setSubmitError(error.message);
        const backendFields = error.fieldErrors;
        if (Object.keys(backendFields).length > 0) {
          setFieldErrors({
            name: backendFields.name,
            email: backendFields.email,
            password: backendFields.password,
          });
        }
      } else {
        setSubmitError('Unable to create your account right now. Please try again.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <AuthShell
      badgeLabel="New Workspace Account"
      title="Create your PayPilot AI account"
      subtitle="Set up your workspace to extract drafts, approve snapshots, and test PayPal Sandbox flows."
      footer={
        <>
          Already have an account?{' '}
          <Link
            to="/login"
            className="font-semibold text-primary-600 hover:text-primary-700 focus:outline-none focus:underline"
          >
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        {submitError ? (
          <AlertBanner tone="error" title="Registration failed" message={submitError} />
        ) : null}

        <FormField
          id="register-name"
          label="Full name"
          type="text"
          name="name"
          autoComplete="name"
          maxLength={255}
          placeholder="Jordan Taylor"
          value={name}
          onChange={(e) => {
            setName(e.target.value);
            clearFieldError('name');
          }}
          error={fieldErrors.name}
          disabled={isSubmitting}
          required
        />

        <FormField
          id="register-email"
          label="Email address"
          type="email"
          name="email"
          autoComplete="email"
          maxLength={255}
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
          id="register-password"
          label="Password"
          name="password"
          autoComplete="new-password"
          maxLength={100}
          placeholder="Minimum 8 characters"
          hint="Use 8 to 100 characters."
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
          {isSubmitting ? 'Creating account...' : 'Create account'}
        </button>
      </form>
    </AuthShell>
  );
}

