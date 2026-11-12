package com.paypilot.dto.customer;

import com.paypilot.dto.invoice.InvoiceResponse;

import java.util.List;

/**
 * Detailed customer projection combining directory contact info and owned invoices.
 */
public record CustomerDetailResponse(
    CustomerResponse customer,
    List<InvoiceResponse> invoices
) {
}

