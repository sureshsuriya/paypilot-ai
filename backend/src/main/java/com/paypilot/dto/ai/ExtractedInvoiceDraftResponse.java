package com.paypilot.dto.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Public API response for AI invoice draft extraction.
 * Always requires human review and confirmation via POST /api/invoices.
 */
public record ExtractedInvoiceDraftResponse(
    Long extractionLogId,
    boolean aiGenerated,
    boolean requiresHumanReview,
    String label,
    String sourceMode,
    String modelUsed,
    BigDecimal confidence,
    String assumptions,
    ExtractedDraftFields draft,
    List<String> missingFields,
    Map<String, String> validationErrors,
    boolean readyToSave
) {
}

