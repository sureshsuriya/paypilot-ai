package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.customer.CreateCustomerRequest;
import com.paypilot.dto.customer.CustomerDetailResponse;
import com.paypilot.dto.customer.CustomerResponse;
import com.paypilot.dto.customer.UpdateCustomerRequest;
import com.paypilot.dto.invoice.InvoiceResponse;
import com.paypilot.exception.DuplicateResourceException;
import com.paypilot.exception.ForbiddenOperationException;
import com.paypilot.exception.ResourceNotFoundException;
import com.paypilot.model.AuditActor;
import com.paypilot.model.Customer;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.User;
import com.paypilot.repository.CustomerRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.UserRepository;
import com.paypilot.util.PhoneNumberValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Authoritative service for seller-scoped customer management, safe backfill/sync
 * from existing invoices, and E.164 phone contact normalization.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final InvoiceSnapshotService invoiceSnapshotService;
    private final AuditLogService auditLogService;

    @Transactional
    public List<CustomerResponse> listCustomers(Long authenticatedUserId, String searchQuery) {
        User seller = userRepository.findById(authenticatedUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found."));

        syncCustomersFromInvoicesForSeller(seller);

        List<Invoice> sellerInvoices = invoiceRepository.findByUserIdOrderByCreatedAtDesc(authenticatedUserId);
        Map<Long, Long> countByCustomerId = new HashMap<>();
        for (Invoice invoice : sellerInvoices) {
            if (invoice.getCustomer() != null) {
                countByCustomerId.merge(invoice.getCustomer().getId(), 1L, Long::sum);
            }
        }

        String normalizedSearch = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.ROOT) : "";
        return customerRepository.findByUserIdOrderByNameAsc(authenticatedUserId).stream()
            .filter(customer -> matchesSearch(customer, normalizedSearch))
            .map(customer -> CustomerResponse.from(customer, countByCustomerId.getOrDefault(customer.getId(), 0L)))
            .toList();
    }

    @Transactional
    public CustomerDetailResponse getCustomer(Long authenticatedUserId, Long customerId) {
        Customer customer = findOwnedCustomer(authenticatedUserId, customerId);
        syncCustomersFromInvoicesForSeller(customer.getUser());

        List<InvoiceResponse> invoices = invoiceRepository
            .findByCustomerIdAndUserIdOrderByCreatedAtDesc(customer.getId(), authenticatedUserId).stream()
            .map(inv -> InvoiceResponse.from(inv, invoiceSnapshotService.computeHash(inv)))
            .toList();

        return new CustomerDetailResponse(
            CustomerResponse.from(customer, invoices.size()),
            invoices
        );
    }

    @Transactional
    public CustomerResponse createCustomer(
            Long authenticatedUserId,
            CreateCustomerRequest request,
            String ipAddress) {
        User seller = userRepository.findById(authenticatedUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found."));

        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        if (customerRepository.existsByUserIdAndEmailIgnoreCase(authenticatedUserId, normalizedEmail)) {
            throw new DuplicateResourceException("A customer with this email already exists in your directory.");
        }

        String normalizedPhone = PhoneNumberValidator.normalizeOptional(request.phone());
        Customer customer = Customer.builder()
            .user(seller)
            .name(request.name().trim())
            .email(normalizedEmail)
            .phone(normalizedPhone)
            .company(trimToNull(request.company()))
            .notes(trimToNull(request.notes()))
            .build();

        Customer saved = customerRepository.saveAndFlush(customer);
        long linkedCount = linkUnlinkedInvoicesByEmail(seller.getId(), saved);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("customerId", saved.getId());
        auditDetails.put("email", saved.getEmail());
        auditDetails.put("hasPhone", saved.getPhone() != null);
        auditLogService.logEvent(
            authenticatedUserId,
            "CUSTOMER",
            saved.getId(),
            "CUSTOMER_CREATED",
            AuditActor.USER,
            auditDetails,
            ipAddress
        );

        return CustomerResponse.from(saved, linkedCount);
    }

    @Transactional
    public CustomerResponse updateCustomer(
            Long authenticatedUserId,
            Long customerId,
            UpdateCustomerRequest request,
            String ipAddress) {
        Customer customer = findOwnedCustomer(authenticatedUserId, customerId);

        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        if (!normalizedEmail.equalsIgnoreCase(customer.getEmail())
                && customerRepository.existsByUserIdAndEmailIgnoreCase(authenticatedUserId, normalizedEmail)) {
            throw new DuplicateResourceException("Another customer in your directory already uses this email.");
        }

        String normalizedPhone = PhoneNumberValidator.normalizeOptional(request.phone());
        customer.setName(request.name().trim());
        customer.setEmail(normalizedEmail);
        customer.setPhone(normalizedPhone);
        customer.setCompany(trimToNull(request.company()));
        customer.setNotes(trimToNull(request.notes()));

        Customer saved = customerRepository.saveAndFlush(customer);

        // Safely sync updated phone onto non-PAID invoices without altering snapshot-protected fields.
        List<Invoice> customerInvoices = invoiceRepository.findByCustomerIdAndUserIdOrderByCreatedAtDesc(
            saved.getId(), authenticatedUserId
        );
        for (Invoice invoice : customerInvoices) {
            if (invoice.getStatus() == InvoiceStatus.DRAFT || invoice.getStatus() == InvoiceStatus.APPROVED) {
                invoice.setClientPhone(normalizedPhone);
                invoiceRepository.save(invoice);
            }
        }
        linkUnlinkedInvoicesByEmail(authenticatedUserId, saved);

        long totalInvoices = invoiceRepository
            .findByCustomerIdAndUserIdOrderByCreatedAtDesc(saved.getId(), authenticatedUserId)
            .size();

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("customerId", saved.getId());
        auditDetails.put("email", saved.getEmail());
        auditDetails.put("hasPhone", saved.getPhone() != null);
        auditLogService.logEvent(
            authenticatedUserId,
            "CUSTOMER",
            saved.getId(),
            "CUSTOMER_UPDATED",
            AuditActor.USER,
            auditDetails,
            ipAddress
        );

        return CustomerResponse.from(saved, totalInvoices);
    }

    /**
     * Links an invoice (during create/edit) to an existing or newly created seller-scoped Customer
     * and synchronizes optional E.164 phone contact information cleanly.
     */
    @Transactional
    public Customer resolveAndLinkForInvoice(
            User owner,
            Invoice invoice,
            Long requestedCustomerId,
            String normalizedPhone,
            boolean inheritCustomerPhoneIfBlank) {
        Customer customer;
        if (requestedCustomerId != null) {
            customer = findOwnedCustomer(owner.getId(), requestedCustomerId);
        } else {
            String email = invoice.getClientEmail().trim().toLowerCase(Locale.ROOT);
            customer = customerRepository.findByUserIdAndEmailIgnoreCase(owner.getId(), email)
                .orElseGet(() -> customerRepository.saveAndFlush(Customer.builder()
                    .user(owner)
                    .name(invoice.getClientName().trim())
                    .email(email)
                    .phone(normalizedPhone)
                    .build()));
        }

        if (normalizedPhone != null) {
            invoice.setClientPhone(normalizedPhone);
            if (customer.getPhone() == null || customer.getPhone().isBlank()) {
                customer.setPhone(normalizedPhone);
                customer = customerRepository.saveAndFlush(customer);
            }
        } else if (inheritCustomerPhoneIfBlank
                && (invoice.getClientPhone() == null || invoice.getClientPhone().isBlank())
                && customer.getPhone() != null && !customer.getPhone().isBlank()) {
            invoice.setClientPhone(customer.getPhone());
        }

        invoice.setCustomer(customer);
        return customer;
    }

    private void syncCustomersFromInvoicesForSeller(User seller) {
        List<Invoice> invoices = invoiceRepository.findByUserIdOrderByCreatedAtDesc(seller.getId());
        if (invoices.isEmpty()) {
            return;
        }

        Map<String, Customer> byEmail = new HashMap<>();
        for (Customer existing : customerRepository.findByUserIdOrderByNameAsc(seller.getId())) {
            byEmail.put(existing.getEmail().toLowerCase(Locale.ROOT), existing);
        }

        for (Invoice invoice : invoices) {
            if (invoice.getCustomer() != null) {
                Customer linked = invoice.getCustomer();
                if ((linked.getPhone() == null || linked.getPhone().isBlank())
                        && invoice.getClientPhone() != null && !invoice.getClientPhone().isBlank()) {
                    linked.setPhone(invoice.getClientPhone());
                    customerRepository.save(linked);
                }
                continue;
            }

            if (invoice.getClientEmail() == null || invoice.getClientEmail().isBlank()) {
                continue;
            }
            String emailKey = invoice.getClientEmail().trim().toLowerCase(Locale.ROOT);
            Customer customer = byEmail.get(emailKey);
            if (customer == null) {
                customer = customerRepository.saveAndFlush(Customer.builder()
                    .user(seller)
                    .name(invoice.getClientName() != null && !invoice.getClientName().isBlank()
                        ? invoice.getClientName().trim()
                        : emailKey)
                    .email(emailKey)
                    .phone(trimToNull(invoice.getClientPhone()))
                    .build());
                byEmail.put(emailKey, customer);
            } else if ((customer.getPhone() == null || customer.getPhone().isBlank())
                    && invoice.getClientPhone() != null && !invoice.getClientPhone().isBlank()) {
                customer.setPhone(invoice.getClientPhone().trim());
                customer = customerRepository.saveAndFlush(customer);
            }

            invoice.setCustomer(customer);
            if ((invoice.getClientPhone() == null || invoice.getClientPhone().isBlank())
                    && customer.getPhone() != null
                    && (invoice.getStatus() == InvoiceStatus.DRAFT || invoice.getStatus() == InvoiceStatus.APPROVED)) {
                invoice.setClientPhone(customer.getPhone());
            }
            invoiceRepository.save(invoice);
        }
    }

    private long linkUnlinkedInvoicesByEmail(Long sellerUserId, Customer customer) {
        List<Invoice> sellerInvoices = invoiceRepository.findByUserIdOrderByCreatedAtDesc(sellerUserId);
        long count = 0;
        for (Invoice invoice : sellerInvoices) {
            if (invoice.getCustomer() != null && invoice.getCustomer().getId().equals(customer.getId())) {
                count++;
            } else if (invoice.getCustomer() == null
                    && invoice.getClientEmail() != null
                    && invoice.getClientEmail().equalsIgnoreCase(customer.getEmail())) {
                invoice.setCustomer(customer);
                if ((invoice.getClientPhone() == null || invoice.getClientPhone().isBlank())
                        && customer.getPhone() != null
                        && (invoice.getStatus() == InvoiceStatus.DRAFT || invoice.getStatus() == InvoiceStatus.APPROVED)) {
                    invoice.setClientPhone(customer.getPhone());
                }
                invoiceRepository.save(invoice);
                count++;
            }
        }
        return count;
    }

    private Customer findOwnedCustomer(Long authenticatedUserId, Long customerId) {
        Customer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> new ResourceNotFoundException("Customer not found."));
        if (!customer.getUser().getId().equals(authenticatedUserId)) {
            throw new ForbiddenOperationException("You do not have permission to access this customer.");
        }
        return customer;
    }

    private boolean matchesSearch(Customer customer, String normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            return true;
        }
        return containsIgnoreCase(customer.getName(), normalizedQuery)
            || containsIgnoreCase(customer.getEmail(), normalizedQuery)
            || containsIgnoreCase(customer.getPhone(), normalizedQuery)
            || containsIgnoreCase(customer.getCompany(), normalizedQuery);
    }

    private boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
