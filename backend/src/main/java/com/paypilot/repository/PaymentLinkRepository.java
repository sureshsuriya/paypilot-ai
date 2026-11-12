package com.paypilot.repository;

import com.paypilot.model.PaymentLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentLinkRepository extends JpaRepository<PaymentLink, Long> {

    Optional<PaymentLink> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pl FROM PaymentLink pl WHERE pl.tokenHash = :tokenHash")
    Optional<PaymentLink> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<PaymentLink> findByActiveInvoiceId(Long activeInvoiceId);

    List<PaymentLink> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);

    List<PaymentLink> findByUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByTokenHash(String tokenHash);
}

