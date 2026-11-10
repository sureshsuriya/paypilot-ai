package com.paypilot.repository;

import com.paypilot.model.AiExtractionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiExtractionLogRepository extends JpaRepository<AiExtractionLog, Long> {

    List<AiExtractionLog> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);
}

