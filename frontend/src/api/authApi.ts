import { apiClient } from './client';
import type {
  AuthResponse,
  HealthResponse,
  LoginRequest,
  RegisterRequest,
} from '@/types';

export const authApi = {
  async login(request: LoginRequest): Promise<AuthResponse> {
    const { data } = await apiClient.post<AuthResponse>('/auth/login', request);
    return data;
  },

  async register(request: RegisterRequest): Promise<AuthResponse> {
    const { data } = await apiClient.post<AuthResponse>('/auth/register', request);
    return data;
  },

  async getHealth(): Promise<HealthResponse> {
    const { data } = await apiClient.get<HealthResponse>('/health');
    return data;
  },
};

