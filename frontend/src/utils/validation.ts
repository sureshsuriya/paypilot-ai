import { parseExactCents } from './money';

export interface LoginFormErrors {
  email?: string;
  password?: string;
}

export interface RegisterFormErrors {
  name?: string;
  email?: string;
  password?: string;
}

export interface InvoiceDraftFormValues {
  clientName: string;
  clientEmail: string;
  clientPhone: string;
  description: string;
  amount: string;
  currency: string;
  dueDate: string;
}

export interface InvoiceDraftFormErrors {
  clientName?: string;
  clientEmail?: string;
  clientPhone?: string;
  description?: string;
  amount?: string;
  currency?: string;
  dueDate?: string;
}

export interface CustomerFormValues {
  name: string;
  email: string;
  phone: string;
  company: string;
  notes: string;
}

export interface CustomerFormErrors {
  name?: string;
  email?: string;
  phone?: string;
  company?: string;
  notes?: string;
}

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CURRENCY_REGEX = /^[A-Za-z]{3}$/;
const ISO_DATE_REGEX = /^(\d{4})-(\d{2})-(\d{2})$/;
const E164_PHONE_REGEX = /^\+[1-9]\d{6,14}$/;

export function normalizeAndValidateOptionalPhone(rawPhone?: string | null): {
  normalized: string | null;
  error?: string;
} {
  if (!rawPhone || !rawPhone.trim()) {
    return { normalized: null };
  }
  const compact = rawPhone.trim().replace(/[\s\-().]+/g, '');
  if (!E164_PHONE_REGEX.test(compact)) {
    return {
      normalized: null,
      error:
        'Include country code starting with + and 7 to 15 digits (for example, +14155552671).',
    };
  }
  return { normalized: compact };
}

export function getTodayLocalIsoDate(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function validateEmailValue(rawEmail: string): string | undefined {
  const email = rawEmail.trim();
  if (!email) {
    return 'Email is required.';
  }
  if (email.length > 255) {
    return 'Email must not exceed 255 characters.';
  }
  if (!EMAIL_REGEX.test(email)) {
    return 'Enter a valid email address.';
  }
  return undefined;
}

export function validateLoginFields(email: string, password: string): LoginFormErrors {
  const errors: LoginFormErrors = {};
  const emailError = validateEmailValue(email);
  if (emailError) {
    errors.email = emailError;
  }
  if (!password) {
    errors.password = 'Password is required.';
  }
  return errors;
}

export function validateRegisterFields(
  name: string,
  email: string,
  password: string
): RegisterFormErrors {
  const errors: RegisterFormErrors = {};
  const trimmedName = name.trim();
  if (!trimmedName) {
    errors.name = 'Full name is required.';
  } else if (trimmedName.length > 255) {
    errors.name = 'Name must not exceed 255 characters.';
  }

  const emailError = validateEmailValue(email);
  if (emailError) {
    errors.email = emailError;
  }

  if (!password) {
    errors.password = 'Password is required.';
  } else if (password.length < 8 || password.length > 100) {
    errors.password = 'Password must be between 8 and 100 characters.';
  }

  return errors;
}

export function validateExtractionPrompt(rawPrompt: string): string | undefined {
  const trimmed = rawPrompt.trim();
  if (!trimmed) {
    return 'Prompt is required.';
  }
  if (trimmed.length < 3 || trimmed.length > 2000) {
    return 'Prompt must be between 3 and 2000 characters.';
  }
  return undefined;
}

function isValidCalendarDate(dateStr: string): boolean {
  const match = ISO_DATE_REGEX.exec(dateStr);
  if (!match) {
    return false;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const candidate = new Date(Date.UTC(year, month - 1, day));
  return (
    candidate.getUTCFullYear() === year &&
    candidate.getUTCMonth() === month - 1 &&
    candidate.getUTCDate() === day
  );
}

/**
 * Client-side validation mirroring Java CreateInvoiceRequest bean validation annotations.
 */
export function validateInvoiceDraftForm(
  values: InvoiceDraftFormValues
): InvoiceDraftFormErrors {
  const errors: InvoiceDraftFormErrors = {};

  const clientName = values.clientName.trim();
  if (!clientName) {
    errors.clientName = 'Client name is required.';
  } else if (clientName.length > 255) {
    errors.clientName = 'Client name must not exceed 255 characters.';
  }

  const clientEmailError = validateEmailValue(values.clientEmail);
  if (clientEmailError) {
    errors.clientEmail = clientEmailError.replace('Email', 'Client email');
  }

  const phoneCheck = normalizeAndValidateOptionalPhone(values.clientPhone);
  if (phoneCheck.error) {
    errors.clientPhone = phoneCheck.error;
  }

  const description = values.description.trim();
  if (!description) {
    errors.description = 'Description is required.';
  } else if (description.length > 5000) {
    errors.description = 'Description must not exceed 5000 characters.';
  }

  const rawAmount = values.amount.trim();
  if (!rawAmount) {
    errors.amount = 'Amount is required.';
  } else {
    const cents = parseExactCents(rawAmount);
    if (cents === null) {
      errors.amount =
        'Amount must be a positive decimal with at most 13 integer digits and 2 decimal places.';
    } else if (cents < 1n) {
      errors.amount = 'Amount must be at least 0.01.';
    }
  }

  const currency = values.currency.trim();
  if (!currency) {
    errors.currency = 'Currency is required.';
  } else if (!CURRENCY_REGEX.test(currency)) {
    errors.currency = 'Currency must be a 3-letter ISO code (e.g. USD).';
  }

  const dueDate = values.dueDate.trim();
  if (!dueDate) {
    errors.dueDate = 'Due date is required.';
  } else if (!isValidCalendarDate(dueDate)) {
    errors.dueDate = 'Due date must be a valid YYYY-MM-DD date.';
  } else if (dueDate < getTodayLocalIsoDate()) {
    errors.dueDate = 'Due date must be today or in the future.';
  }

  return errors;
}

export function validateCustomerForm(values: CustomerFormValues): CustomerFormErrors {
  const errors: CustomerFormErrors = {};

  const name = values.name.trim();
  if (!name) {
    errors.name = 'Customer name is required.';
  } else if (name.length > 255) {
    errors.name = 'Customer name must not exceed 255 characters.';
  }

  const emailError = validateEmailValue(values.email);
  if (emailError) {
    errors.email = emailError.replace('Email', 'Customer email');
  }

  const phoneCheck = normalizeAndValidateOptionalPhone(values.phone);
  if (phoneCheck.error) {
    errors.phone = phoneCheck.error;
  }

  if (values.company.trim().length > 255) {
    errors.company = 'Company name must not exceed 255 characters.';
  }

  if (values.notes.trim().length > 1000) {
    errors.notes = 'Notes must not exceed 1000 characters.';
  }

  return errors;
}
