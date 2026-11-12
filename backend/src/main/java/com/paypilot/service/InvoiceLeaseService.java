package com.paypilot.service;

import com.paypilot.exception.InvalidInvoiceStateException;
import com.paypilot.exception.ResourceNotFoundException;
import com.paypilot.repository.InvoiceRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Coordinates cross-instance atomic database leases on invoices so concurrent
 * order creation, capture, cancellation, and invoice mutation operations never race,
 * without holding database transactions or connections open across PayPal HTTP calls.
 */
@Service
public class InvoiceLeaseService {

    private final InvoiceRepository invoiceRepository;
    private final TransactionTemplate standaloneTransaction;
    private final Clock clock;
    private final long leaseTtlSeconds;

    public InvoiceLeaseService(
            InvoiceRepository invoiceRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("paymentClock") Clock clock,
            @Value("${payment.lease-ttl-seconds:30}") long leaseTtlSeconds) {
        this.invoiceRepository = invoiceRepository;
        this.standaloneTransaction = new TransactionTemplate(transactionManager);
        this.standaloneTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.clock = clock;
        this.leaseTtlSeconds = Math.max(1L, leaseTtlSeconds);
    }

    /**
     * Atomically acquires a cross-instance short-TTL lease on the invoice row.
     * Uses an atomic conditional SQL UPDATE (InnoDB exclusive row lock).
     */
    public String acquireLease(Long invoiceId) {
        String ownerToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plusSeconds(leaseTtlSeconds);

        Boolean acquired = standaloneTransaction.execute(status -> {
            int updated = invoiceRepository.tryAcquirePaymentLease(invoiceId, ownerToken, now, expiresAt);
            if (updated == 1) {
                return Boolean.TRUE;
            }
            if (!invoiceRepository.existsById(invoiceId)) {
                throw new ResourceNotFoundException("Invoice not found.");
            }
            return Boolean.FALSE;
        });

        if (!Boolean.TRUE.equals(acquired)) {
            throw new InvalidInvoiceStateException(
                "Another payment or invoice operation is currently in progress for this invoice. Please retry shortly."
            );
        }
        return ownerToken;
    }

    /**
     * Releases the lease only if still owned by ownerToken (owner-fenced release).
     */
    public void releaseLease(Long invoiceId, String ownerToken) {
        if (invoiceId == null || ownerToken == null || ownerToken.isBlank()) {
            return;
        }
        try {
            standaloneTransaction.execute(status -> {
                invoiceRepository.releasePaymentLease(invoiceId, ownerToken);
                return null;
            });
        } catch (RuntimeException ignored) {
            // Lease expires automatically after TTL if release fails during shutdown/network blip
        }
    }

    public long getLeaseTtlSeconds() {
        return leaseTtlSeconds;
    }
}

