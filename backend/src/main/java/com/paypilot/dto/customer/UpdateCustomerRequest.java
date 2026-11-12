package com.paypilot.dto.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Validated request payload to update an existing seller-scoped customer record.
 */
public record UpdateCustomerRequest(
    @NotBlank(message = "Customer name is required")
    @Size(max = 255, message = "Customer name must not exceed 255 characters")
    String name,

    @NotBlank(message = "Customer email is required")
    @Email(message = "Customer email must be valid")
    @Size(max = 255, message = "Customer email must not exceed 255 characters")
    String email,

    @Size(max = 32, message = "Phone number must not exceed 32 characters")
    String phone,

    @Size(max = 255, message = "Company name must not exceed 255 characters")
    String company,

    @Size(max = 1000, message = "Notes must not exceed 1000 characters")
    String notes
) {
}

