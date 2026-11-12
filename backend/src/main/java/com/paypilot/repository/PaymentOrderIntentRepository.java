package com.paypilot.repository;

import com.paypilot.model.PaymentOrderIntent;
import com.paypilot.model.PaymentOrderIntentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentOrderIntentRepository extends JpaRepository<PaymentOrderIntent, Long> {

    Optional<PaymentOrderIntent> findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(
        Long invoiceId,
        PaymentOrderIntentStatus status
    );

    List<PaymentOrderIntent> findByInvoiceIdAndStatus(Long invoiceId, PaymentOrderIntentStatus status);

    List<PaymentOrderIntent> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);

    @Query("""
        SELECT COALESCE(MAX(poi.attemptNumber), -1)
        FROM PaymentOrderIntent poi
        WHERE poi.invoice.id = :invoiceId
          AND poi.invoiceSnapshotHash = :snapshotHash
        """)
    int findMaxAttemptNumber(
        @Param("invoiceId") Long invoiceId,
        @Param("snapshotHash") String snapshotHash
    );
}

