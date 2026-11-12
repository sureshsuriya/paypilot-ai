import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { ApiClientError, type ApiErrorResponse } from '@/types';
import { clearAuthSession, getAccessToken } from '@/utils/authStorage';

const PUBLIC_AUTH_PATHS = ['/auth/login', '/auth/register'];
const PUBLIC_NO_TOKEN_PATHS = [...PUBLIC_AUTH_PATHS, '/health'];

let unauthorizedHandler: (() => void) | null = null;

export function registerUnauthorizedHandler(handler: (() => void) | null): () => void {
  unauthorizedHandler = handler;
  return () => {
    if (unauthorizedHandler === handler) {
      unauthorizedHandler = null;
    }
  };
}

/**
 * Resolves the Spring Boot backend API base URL.
 * Defaults to `/api` so Vite dev proxy (`http://localhost:5173/api` -> `http://localhost:8080/api`)
 * works without requiring a `.env` file, while allowing explicit `VITE_API_BASE_URL` overrides.
 */
export function resolveApiBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL;
  const raw = typeof configured === 'string' && configured.trim().length > 0
    ? configured.trim()
    : '/api';

  const normalized = raw.length > 1 ? raw.replace(/\/+$/, '') : raw;

  // Guard against misconfiguring the frontend to call ai-service (:3001) or PayPal directly.
  const lower = normalized.toLowerCase();
  if (lower.includes('paypal.com') || lower.includes(':3001')) {
    throw new Error(
      'Invalid VITE_API_BASE_URL: frontend must communicate only with Spring Boot.'
    );
  }

  return normalized;
}

function matchesEndpoint(url: string | undefined, paths: string[]): boolean {
  if (!url) {
    return false;
  }
  const cleanUrl = url.split('?')[0];
  return paths.some((path) => cleanUrl === path || cleanUrl.endsWith(path));
}

function extractFieldErrors(raw: unknown): Record<string, string> | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return undefined;
  }
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(raw)) {
    if (typeof value === 'string' && value.trim().length > 0) {
      result[key] = value;
    }
  }
  return Object.keys(result).length > 0 ? result : undefined;
}

export function normalizeApiError(error: unknown): ApiClientError {
  if (error instanceof ApiClientError) {
    return error;
  }

  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<Partial<ApiErrorResponse>>;
    const response = axiosError.response;

    if (!response) {
      return new ApiClientError({
        status: 0,
        error: 'Network Error',
        message: 'Unable to reach the PayPilot backend server. Please verify it is running and retry.',
        isNetworkError: true,
      });
    }

    const status = response.status;
    const payload = response.data && typeof response.data === 'object'
      ? response.data
      : undefined;

    const fieldErrors = extractFieldErrors(payload?.fieldErrors);
    const backendMessage = typeof payload?.message === 'string' && payload.message.trim().length > 0
      ? payload.message
      : undefined;
    const errorLabel = typeof payload?.error === 'string' && payload.error.trim().length > 0
      ? payload.error
      : `HTTP ${status}`;

    return new ApiClientError({
      status,
      error: errorLabel,
      message: backendMessage ?? `Request failed with status ${status}.`,
      path: typeof payload?.path === 'string' ? payload.path : '',
      timestamp: typeof payload?.timestamp === 'string' ? payload.timestamp : undefined,
      fieldErrors,
    });
  }

  return new ApiClientError({
    status: 0,
    error: 'Unexpected Error',
    message: error instanceof Error ? error.message : 'An unexpected error occurred.',
  });
}

export const apiClient = axios.create({
  baseURL: resolveApiBaseUrl(),
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 30000,
});

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (!matchesEndpoint(config.url, PUBLIC_NO_TOKEN_PATHS)) {
    const token = getAccessToken();
    if (token && !config.headers.Authorization) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const normalized = normalizeApiError(error);
    const requestUrl = axios.isAxiosError(error) ? error.config?.url : undefined;

    if (normalized.isUnauthorized && !matchesEndpoint(requestUrl, PUBLIC_AUTH_PATHS)) {
      clearAuthSession();
      unauthorizedHandler?.();
    }

    return Promise.reject(normalized);
  }
);
