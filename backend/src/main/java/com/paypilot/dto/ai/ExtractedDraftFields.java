package com.paypilot.dto.ai;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Authoritatively validated draft invoice fields returned to the user for review.
 */
public record ExtractedDraftFields(
    String clientName,
    String clientEmail,
    String description,
    BigDecimal amount,
    String currency,
    LocalDate dueDate
) {
}

