import type { AuthResponse, UserSummaryDto } from '@/types';
import { clearAllCachedShareLinks } from './shareLinkStorage';

/**
 * Browser localStorage key used to persist JWT session across page reloads
 * and full-page PayPal Sandbox checkout redirects.
 *
 * Security note: Browser localStorage is accessible to same-origin scripts (not
 * immune to XSS) and is used only for MVP client session continuity — never
 * store passwords here, and always rely on Spring Security backend validation.
 */
export const AUTH_STORAGE_KEY = 'paypilot.auth.session';

export interface StoredAuthSession {
  token: string;
  tokenType: string;
  expiresAtEpochMs: number;
  user: UserSummaryDto;
}

function getLocalStorageSafe(): Storage | null {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function isValidUserSummary(candidate: unknown): candidate is UserSummaryDto {
  if (!candidate || typeof candidate !== 'object') {
    return false;
  }
  const user = candidate as Partial<UserSummaryDto>;
  return (
    typeof user.id === 'number' &&
    typeof user.name === 'string' &&
    typeof user.email === 'string' &&
    (user.role === 'USER' || user.role === 'ADMIN')
  );
}

/**
 * Best-effort client-side read of the JWT `exp` claim solely for UI session pruning.
 * Never used as a security trust boundary (backend JwtAuthFilter verifies signatures).
 */
export function extractJwtExpirationEpochMs(token: string): number | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3 || !parts[1]) {
      return null;
    }
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const decodedJson = atob(padded);
    const payload = JSON.parse(decodedJson) as { exp?: unknown };
    if (typeof payload.exp === 'number' && Number.isFinite(payload.exp)) {
      return payload.exp * 1000;
    }
    return null;
  } catch {
    return null;
  }
}

export function saveAuthSession(authResponse: AuthResponse): StoredAuthSession {
  const token = authResponse.token?.trim();
  if (!token || !isValidUserSummary(authResponse.user)) {
    throw new Error('Invalid authentication response payload.');
  }

  const now = Date.now();
  const fallbackExpiry = now + Math.max(0, Number(authResponse.expiresInMs) || 0);
  const jwtExpiry = extractJwtExpirationEpochMs(token);
  const expiresAtEpochMs =
    jwtExpiry !== null ? Math.min(jwtExpiry, fallbackExpiry) : fallbackExpiry;

  const session: StoredAuthSession = {
    token,
    tokenType: authResponse.tokenType || 'Bearer',
    expiresAtEpochMs,
    user: authResponse.user,
  };

  const storage = getLocalStorageSafe();
  if (storage) {
    storage.setItem(AUTH_STORAGE_KEY, JSON.stringify(session));
  }
  return session;
}

export function loadAuthSession(): StoredAuthSession | null {
  const storage = getLocalStorageSafe();
  if (!storage) {
    return null;
  }

  const raw = storage.getItem(AUTH_STORAGE_KEY);
  if (!raw) {
    return null;
  }

  try {
    const parsed = JSON.parse(raw) as Partial<StoredAuthSession>;
    if (
      typeof parsed.token !== 'string' ||
      !parsed.token.trim() ||
      typeof parsed.expiresAtEpochMs !== 'number' ||
      !isValidUserSummary(parsed.user)
    ) {
      storage.removeItem(AUTH_STORAGE_KEY);
      return null;
    }

    if (Date.now() >= parsed.expiresAtEpochMs) {
      storage.removeItem(AUTH_STORAGE_KEY);
      return null;
    }

    return {
      token: parsed.token,
      tokenType: typeof parsed.tokenType === 'string' ? parsed.tokenType : 'Bearer',
      expiresAtEpochMs: parsed.expiresAtEpochMs,
      user: parsed.user,
    };
  } catch {
    storage.removeItem(AUTH_STORAGE_KEY);
    return null;
  }
}

export function getAccessToken(): string | null {
  return loadAuthSession()?.token ?? null;
}

export function clearAuthSession(): void {
  const storage = getLocalStorageSafe();
  if (storage) {
    storage.removeItem(AUTH_STORAGE_KEY);
  }
  clearAllCachedShareLinks();
}

