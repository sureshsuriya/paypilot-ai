package com.paypilot.repository;

import com.paypilot.model.Payment;
import com.paypilot.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);

    Optional<Payment> findByPaypalOrderId(String paypalOrderId);

    Optional<Payment> findByPaypalCaptureId(String paypalCaptureId);

    boolean existsByPaypalOrderId(String paypalOrderId);

    boolean existsByInvoiceIdAndStatus(Long invoiceId, PaymentStatus status);
}

