export interface ExtractInvoicePromptRequest {
  prompt: string;
}

export interface ExtractedDraftFields {
  clientName: string | null;
  clientEmail: string | null;
  description: string | null;
  amount: number | null;
  currency: string | null;
  dueDate: string | null;
}

export interface ExtractedInvoiceDraftResponse {
  extractionLogId: number;
  aiGenerated: boolean;
  requiresHumanReview: boolean;
  label: string;
  sourceMode: string;
  modelUsed: string;
  confidence: number | null;
  assumptions: string | null;
  draft: ExtractedDraftFields;
  missingFields: string[];
  validationErrors: Record<string, string>;
  readyToSave: boolean;
}

