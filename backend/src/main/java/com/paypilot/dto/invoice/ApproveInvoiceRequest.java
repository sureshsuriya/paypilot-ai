package com.paypilot.dto.invoice;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Explicit human approval payload tied to the exact reviewed invoice snapshot hash.
 */
public record ApproveInvoiceRequest(
    @NotBlank(message = "Snapshot hash is required")
    @Size(min = 64, max = 64, message = "Snapshot hash must be a 64-character SHA-256 hex digest")
    String snapshotHash,

    @NotNull(message = "confirmApproval is required")
    @AssertTrue(message = "confirmApproval must be explicitly set to true")
    Boolean confirmApproval
) {
}

