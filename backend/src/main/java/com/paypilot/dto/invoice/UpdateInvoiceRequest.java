package com.paypilot.dto.invoice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Validated request payload to update an existing DRAFT invoice.
 */
public record UpdateInvoiceRequest(
    @NotBlank(message = "Client name is required")
    @Size(max = 255, message = "Client name must not exceed 255 characters")
    String clientName,

    @NotBlank(message = "Client email is required")
    @Email(message = "Client email must be valid")
    @Size(max = 255, message = "Client email must not exceed 255 characters")
    String clientEmail,

    @NotBlank(message = "Description is required")
    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    String description,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
    @Digits(integer = 13, fraction = 2, message = "Amount must have at most 13 integer digits and 2 decimal places")
    BigDecimal amount,

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter ISO code (e.g. USD)")
    String currency,

    @NotNull(message = "Due date is required")
    @FutureOrPresent(message = "Due date must be today or in the future")
    LocalDate dueDate
) {
}

