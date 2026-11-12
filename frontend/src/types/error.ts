export interface ApiErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  fieldErrors?: Record<string, string>;
}

/**
 * Normalized error thrown by the frontend Axios client so UI components can
 * handle HTTP status codes, backend messages, and field validation errors uniformly.
 */
export class ApiClientError extends Error {
  readonly status: number;
  readonly error: string;
  readonly path: string;
  readonly timestamp: string;
  readonly fieldErrors: Record<string, string>;
  readonly isNetworkError: boolean;
  readonly isUnauthorized: boolean;

  constructor(params: {
    status: number;
    error: string;
    message: string;
    path?: string;
    timestamp?: string;
    fieldErrors?: Record<string, string>;
    isNetworkError?: boolean;
  }) {
    super(params.message);
    this.name = 'ApiClientError';
    this.status = params.status;
    this.error = params.error;
    this.path = params.path ?? '';
    this.timestamp = params.timestamp ?? new Date().toISOString();
    this.fieldErrors = params.fieldErrors ?? {};
    this.isNetworkError = Boolean(params.isNetworkError);
    this.isUnauthorized = params.status === 401;
  }
}

