package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.invoice.CreateInvoiceRequest;
import com.paypilot.dto.invoice.InvoiceResponse;
import com.paypilot.dto.invoice.UpdateInvoiceRequest;
import com.paypilot.exception.ForbiddenOperationException;
import com.paypilot.exception.InvalidInvoiceStateException;
import com.paypilot.exception.ResourceNotFoundException;
import com.paypilot.model.AuditActor;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.User;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentRepository;
import com.paypilot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authoritative service for invoice creation, ownership enforcement,
 * draft-only editing/deletion, and audit logging.
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String INVOICE_CODE_CHARS = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int INVOICE_CODE_LENGTH = 6;
    private static final int MAX_GENERATION_ATTEMPTS = 10;
    private static final DateTimeFormatter DATE_PREFIX_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public InvoiceResponse createInvoice(
            Long authenticatedUserId,
            CreateInvoiceRequest request,
            String ipAddress) {

        User owner = userRepository.findById(authenticatedUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found."));

        String invoiceNumber = generateUniqueInvoiceNumber(owner.getId());

        Invoice invoice = Invoice.builder()
            .user(owner)
            .invoiceNumber(invoiceNumber)
            .status(InvoiceStatus.DRAFT)
            .issuedAt(LocalDate.now())
            .aiExtracted(Boolean.FALSE)
            .build();

        applyValidatedFields(
            invoice,
            request.clientName(),
            request.clientEmail(),
            request.description(),
            request.amount(),
            request.currency(),
            request.dueDate()
        );

        Invoice saved = invoiceRepository.save(invoice);

        auditLogService.logEvent(
            owner.getId(),
            "INVOICE",
            saved.getId(),
            "INVOICE_CREATED",
            AuditActor.USER,
            Map.of(
                "invoiceNumber", saved.getInvoiceNumber(),
                "amount", saved.getAmount().toPlainString(),
                "currency", saved.getCurrency(),
                "status", saved.getStatus().name(),
                "dueDate", saved.getDueDate().toString()
            ),
            ipAddress
        );

        return InvoiceResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listInvoices(Long authenticatedUserId, InvoiceStatus statusFilter) {
        List<Invoice> invoices = statusFilter != null
            ? invoiceRepository.findByUserIdAndStatusOrderByCreatedAtDesc(authenticatedUserId, statusFilter)
            : invoiceRepository.findByUserIdOrderByCreatedAtDesc(authenticatedUserId);

        return invoices.stream()
            .map(InvoiceResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoice(Long authenticatedUserId, Long invoiceId) {
        Invoice invoice = findOwnedInvoice(authenticatedUserId, invoiceId);
        return InvoiceResponse.from(invoice);
    }

    @Transactional
    public InvoiceResponse updateDraftInvoice(
            Long authenticatedUserId,
            Long invoiceId,
            UpdateInvoiceRequest request,
            String ipAddress) {

        Invoice invoice = findOwnedInvoice(authenticatedUserId, invoiceId);
        ensureDraft(invoice, "modified");

        BigDecimal previousAmount = invoice.getAmount();
        String previousCurrency = invoice.getCurrency();

        applyValidatedFields(
            invoice,
            request.clientName(),
            request.clientEmail(),
            request.description(),
            request.amount(),
            request.currency(),
            request.dueDate()
        );

        Invoice saved = invoiceRepository.save(invoice);

        auditLogService.logEvent(
            authenticatedUserId,
            "INVOICE",
            saved.getId(),
            "INVOICE_UPDATED",
            AuditActor.USER,
            Map.of(
                "invoiceNumber", saved.getInvoiceNumber(),
                "previousAmount", previousAmount.toPlainString(),
                "previousCurrency", previousCurrency,
                "newAmount", saved.getAmount().toPlainString(),
                "newCurrency", saved.getCurrency(),
                "dueDate", saved.getDueDate().toString()
            ),
            ipAddress
        );

        return InvoiceResponse.from(saved);
    }

    @Transactional
    public void deleteDraftInvoice(Long authenticatedUserId, Long invoiceId, String ipAddress) {
        Invoice invoice = findOwnedInvoice(authenticatedUserId, invoiceId);
        ensureDraft(invoice, "deleted");

        if (!paymentRepository.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).isEmpty()) {
            throw new InvalidInvoiceStateException("Invoices with associated payment records cannot be deleted.");
        }

        String invoiceNumber = invoice.getInvoiceNumber();
        BigDecimal amount = invoice.getAmount();
        String currency = invoice.getCurrency();

        invoiceRepository.delete(invoice);

        auditLogService.logEvent(
            authenticatedUserId,
            "INVOICE",
            invoiceId,
            "INVOICE_DELETED",
            AuditActor.USER,
            Map.of(
                "invoiceNumber", invoiceNumber,
                "amount", amount.toPlainString(),
                "currency", currency,
                "previousStatus", InvoiceStatus.DRAFT.name()
            ),
            ipAddress
        );
    }

    private Invoice findOwnedInvoice(Long authenticatedUserId, Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));

        if (!invoice.getUser().getId().equals(authenticatedUserId)) {
            throw new ForbiddenOperationException("You do not have permission to access this invoice.");
        }
        return invoice;
    }

    private void ensureDraft(Invoice invoice, String operation) {
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvalidInvoiceStateException(
                "Only DRAFT invoices can be " + operation + ". Current status: " + invoice.getStatus()
            );
        }
    }

    private void applyValidatedFields(
            Invoice invoice,
            String clientName,
            String clientEmail,
            String description,
            BigDecimal amount,
            String currency,
            LocalDate dueDate) {

        invoice.setClientName(clientName.trim());
        invoice.setClientEmail(clientEmail.trim().toLowerCase(Locale.ROOT));
        invoice.setDescription(description.trim());
        invoice.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        invoice.setCurrency(currency.trim().toUpperCase(Locale.ROOT));
        invoice.setDueDate(dueDate);
    }

    private String generateUniqueInvoiceNumber(Long userId) {
        String datePart = LocalDate.now().format(DATE_PREFIX_FORMAT);
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            StringBuilder suffix = new StringBuilder(INVOICE_CODE_LENGTH);
            for (int i = 0; i < INVOICE_CODE_LENGTH; i++) {
                suffix.append(INVOICE_CODE_CHARS.charAt(SECURE_RANDOM.nextInt(INVOICE_CODE_CHARS.length())));
            }
            String candidate = "INV-" + datePart + "-" + suffix;
            if (!invoiceRepository.existsByInvoiceNumberAndUserId(candidate, userId)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique invoice number. Please retry.");
    }
}

