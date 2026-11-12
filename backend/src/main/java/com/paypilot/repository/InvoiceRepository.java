package com.paypilot.repository;

import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Invoice i
        SET i.paymentLockOwner = :ownerToken,
            i.paymentLockUntil = :expiresAt
        WHERE i.id = :invoiceId
          AND (i.paymentLockOwner IS NULL OR i.paymentLockUntil <= :now)
        """)
    int tryAcquirePaymentLease(
        @Param("invoiceId") Long invoiceId,
        @Param("ownerToken") String ownerToken,
        @Param("now") LocalDateTime now,
        @Param("expiresAt") LocalDateTime expiresAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Invoice i
        SET i.paymentLockOwner = NULL,
            i.paymentLockUntil = NULL
        WHERE i.id = :invoiceId
          AND i.paymentLockOwner = :ownerToken
        """)
    int releasePaymentLease(
        @Param("invoiceId") Long invoiceId,
        @Param("ownerToken") String ownerToken
    );

    List<Invoice> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Invoice> findByIdAndUserId(Long id, Long userId);

    Optional<Invoice> findByInvoiceNumberAndUserId(String invoiceNumber, Long userId);

    Optional<Invoice> findByPaypalInvoiceId(String paypalInvoiceId);

    List<Invoice> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, InvoiceStatus status);

    List<Invoice> findByCustomerIdAndUserIdOrderByCreatedAtDesc(Long customerId, Long userId);

    boolean existsByInvoiceNumberAndUserId(String invoiceNumber, Long userId);
}

