import { apiClient } from './client';
import type {
  ExtractedInvoiceDraftResponse,
  ExtractInvoicePromptRequest,
} from '@/types';

/**
 * Calls the authenticated Spring Boot proxy (`POST /api/ai/extract-invoice`).
 * Never connects directly to the internal Node.js ai-service or Google Gemini.
 */
export const aiApi = {
  async extractInvoiceDraft(
    request: ExtractInvoicePromptRequest
  ): Promise<ExtractedInvoiceDraftResponse> {
    const { data } = await apiClient.post<ExtractedInvoiceDraftResponse>(
      '/ai/extract-invoice',
      request
    );
    return data;
  },
};

