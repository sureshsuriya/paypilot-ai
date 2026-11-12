import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { authApi, registerUnauthorizedHandler } from '@/api';
import type {
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  UserSummaryDto,
} from '@/types';
import {
  AUTH_STORAGE_KEY,
  clearAuthSession,
  loadAuthSession,
  saveAuthSession,
  type StoredAuthSession,
} from '@/utils/authStorage';

const MAX_TIMEOUT_MS = 2_147_483_647;

export interface AuthContextValue {
  user: UserSummaryDto | null;
  token: string | null;
  expiresAtEpochMs: number | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (request: LoginRequest) => Promise<AuthResponse>;
  register: (request: RegisterRequest) => Promise<AuthResponse>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<StoredAuthSession | null>(() => loadAuthSession());
  const isLoading = false;

  const logout = useCallback(() => {
    clearAuthSession();
    setSession(null);
  }, []);

  useEffect(() => {
    // Re-sync stored state once on mount in case storage changed before hydration
    setSession(loadAuthSession());

    const unregister401 = registerUnauthorizedHandler(() => {
      setSession(null);
    });

    const handleStorageChange = (event: StorageEvent) => {
      if (event.key === null || event.key === AUTH_STORAGE_KEY) {
        setSession(loadAuthSession());
      }
    };

    window.addEventListener('storage', handleStorageChange);
    return () => {
      unregister401();
      window.removeEventListener('storage', handleStorageChange);
    };
  }, []);

  useEffect(() => {
    if (!session) {
      return undefined;
    }

    const remainingMs = session.expiresAtEpochMs - Date.now();
    if (remainingMs <= 0) {
      logout();
      return undefined;
    }

    const timer = window.setTimeout(
      () => {
        if (!loadAuthSession()) {
          setSession(null);
        } else if (Date.now() >= session.expiresAtEpochMs) {
          logout();
        }
      },
      Math.min(remainingMs, MAX_TIMEOUT_MS)
    );

    return () => window.clearTimeout(timer);
  }, [session, logout]);

  const applyAuthSuccess = useCallback((authResponse: AuthResponse): AuthResponse => {
    const stored = saveAuthSession(authResponse);
    setSession(stored);
    return authResponse;
  }, []);

  const login = useCallback(
    async (request: LoginRequest): Promise<AuthResponse> => {
      const response = await authApi.login(request);
      return applyAuthSuccess(response);
    },
    [applyAuthSuccess]
  );

  const register = useCallback(
    async (request: RegisterRequest): Promise<AuthResponse> => {
      const response = await authApi.register(request);
      return applyAuthSuccess(response);
    },
    [applyAuthSuccess]
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      user: session?.user ?? null,
      token: session?.token ?? null,
      expiresAtEpochMs: session?.expiresAtEpochMs ?? null,
      isAuthenticated: Boolean(session?.token && session.expiresAtEpochMs > Date.now()),
      isLoading,
      login,
      register,
      logout,
    }),
    [session, isLoading, login, register, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider.');
  }
  return context;
}
