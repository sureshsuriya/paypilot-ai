package com.paypilot.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Natural-language invoice request submitted by an authenticated user.
 */
public record ExtractInvoicePromptRequest(
    @NotBlank(message = "Prompt is required")
    @Size(min = 3, max = 2000, message = "Prompt must be between 3 and 2000 characters")
    String prompt
) {
}

