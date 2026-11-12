package com.paypilot.dto.customer;

import com.paypilot.model.Customer;

import java.time.LocalDateTime;

/**
 * Seller-scoped customer API projection. Never exposes other sellers' data or credentials.
 */
public record CustomerResponse(
    Long id,
    String name,
    String email,
    String phone,
    String company,
    String notes,
    long invoiceCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static CustomerResponse from(Customer customer, long invoiceCount) {
        return new CustomerResponse(
            customer.getId(),
            customer.getName(),
            customer.getEmail(),
            customer.getPhone(),
            customer.getCompany(),
            customer.getNotes(),
            invoiceCount,
            customer.getCreatedAt(),
            customer.getUpdatedAt()
        );
    }
}
