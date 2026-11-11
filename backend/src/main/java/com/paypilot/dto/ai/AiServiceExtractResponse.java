package com.paypilot.dto.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Internal DTO mirroring the JSON payload returned by ai-service POST /api/ai/extract.
 * All fields are re-validated authoritatively by Java before returning to clients.
 */
public record AiServiceExtractResponse(
    boolean aiGenerated,
    boolean requiresHumanReview,
    String label,
    String sourceMode,
    String modelUsed,
    BigDecimal confidence,
    String assumptions,
    RawDraftPayload draft,
    List<String> missingFields,
    Map<String, String> validationErrors,
    boolean readyToSave,
    TokenUsagePayload tokenUsage
) {
    public record RawDraftPayload(
        String clientName,
        String clientEmail,
        String description,
        BigDecimal amount,
        String currency,
        String dueDate
    ) {
    }

    public record TokenUsagePayload(
        Integer promptTokens,
        Integer completionTokens
    ) {
    }
}

