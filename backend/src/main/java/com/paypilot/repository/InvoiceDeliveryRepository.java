package com.paypilot.repository;

import com.paypilot.model.InvoiceDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceDeliveryRepository extends JpaRepository<InvoiceDelivery, Long> {

    List<InvoiceDelivery> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);

    Optional<InvoiceDelivery> findFirstByInvoiceIdAndChannelAndStatusOrderByCreatedAtDesc(
        Long invoiceId,
        String channel,
        String status
    );
}

