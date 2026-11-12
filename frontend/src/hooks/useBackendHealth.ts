import { useCallback, useEffect, useState } from 'react';
import { authApi } from '@/api';
import type { HealthResponse } from '@/types';

export type BackendHealthStatus = 'checking' | 'online' | 'offline';

export interface BackendHealthState {
  status: BackendHealthStatus;
  lastCheckedAt: string | null;
  service: string | null;
  recheck: () => Promise<void>;
}

const HEALTH_POLL_INTERVAL_MS = 60_000;

/**
 * Periodically checks Spring Boot `/api/health` while the tab is visible
 * and avoids unnecessary aggressive polling.
 */
export function useBackendHealth(): BackendHealthState {
  const [status, setStatus] = useState<BackendHealthStatus>('checking');
  const [lastCheckedAt, setLastCheckedAt] = useState<string | null>(null);
  const [service, setService] = useState<string | null>(null);

  const checkHealth = useCallback(async () => {
    try {
      const response: HealthResponse = await authApi.getHealth();
      if (response.status === 'ok') {
        setStatus('online');
        setService(response.service);
      } else {
        setStatus('offline');
      }
      setLastCheckedAt(new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
    } catch {
      setStatus('offline');
      setLastCheckedAt(new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
    }
  }, []);

  const recheck = useCallback(async () => {
    setStatus('checking');
    await checkHealth();
  }, [checkHealth]);

  useEffect(() => {
    let active = true;

    const runIfActive = async () => {
      try {
        const response: HealthResponse = await authApi.getHealth();
        if (!active) {
          return;
        }
        setStatus(response.status === 'ok' ? 'online' : 'offline');
        setService(response.service);
        setLastCheckedAt(new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
      } catch {
        if (!active) {
          return;
        }
        setStatus('offline');
        setLastCheckedAt(new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));
      }
    };

    void runIfActive();

    const intervalId = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        void runIfActive();
      }
    }, HEALTH_POLL_INTERVAL_MS);

    const handleOnline = () => {
      void runIfActive();
    };
    window.addEventListener('online', handleOnline);

    return () => {
      active = false;
      window.clearInterval(intervalId);
      window.removeEventListener('online', handleOnline);
    };
  }, []);

  return { status, lastCheckedAt, service, recheck };
}

