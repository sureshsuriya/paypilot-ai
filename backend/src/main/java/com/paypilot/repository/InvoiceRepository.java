package com.paypilot.repository;

import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Invoice> findByIdAndUserId(Long id, Long userId);

    Optional<Invoice> findByInvoiceNumberAndUserId(String invoiceNumber, Long userId);

    Optional<Invoice> findByPaypalInvoiceId(String paypalInvoiceId);

    List<Invoice> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, InvoiceStatus status);

    boolean existsByInvoiceNumberAndUserId(String invoiceNumber, Long userId);
}

