package com.paypilot.repository;

import com.paypilot.model.Payment;
import com.paypilot.model.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.paypalOrderId = :paypalOrderId")
    Optional<Payment> findByPaypalOrderIdForUpdate(@Param("paypalOrderId") String paypalOrderId);

    List<Payment> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);

    @Query("""
        SELECT p FROM Payment p
        JOIN FETCH p.invoice inv
        LEFT JOIN FETCH p.paymentLink
        WHERE inv.user.id = :userId
        ORDER BY p.createdAt DESC
        """)
    List<Payment> findAllForSellerOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query("""
        SELECT p FROM Payment p
        JOIN FETCH p.invoice inv
        LEFT JOIN FETCH p.paymentLink
        WHERE inv.user.id = :userId
          AND p.status = :status
        ORDER BY p.createdAt DESC
        """)
    List<Payment> findAllForSellerAndStatusOrderByCreatedAtDesc(
        @Param("userId") Long userId,
        @Param("status") PaymentStatus status
    );

    List<Payment> findByInvoiceIdAndStatusIn(Long invoiceId, Collection<PaymentStatus> statuses);

    Optional<Payment> findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(Long invoiceId, PaymentStatus status);

    Optional<Payment> findFirstByInvoiceIdAndInvoiceSnapshotHashAndStatusInOrderByCreatedAtDesc(
        Long invoiceId,
        String invoiceSnapshotHash,
        Collection<PaymentStatus> statuses
    );

    long countByInvoiceIdAndInvoiceSnapshotHashAndStatusIn(
        Long invoiceId,
        String invoiceSnapshotHash,
        Collection<PaymentStatus> statuses
    );

    Optional<Payment> findByPaypalOrderId(String paypalOrderId);

    Optional<Payment> findByPaypalCaptureId(String paypalCaptureId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    boolean existsByPaypalOrderId(String paypalOrderId);

    boolean existsByInvoiceIdAndStatus(Long invoiceId, PaymentStatus status);

    boolean existsByInvoiceIdAndCaptureRequestedAtIsNotNullAndStatusIn(
        Long invoiceId,
        Collection<PaymentStatus> statuses
    );
}

