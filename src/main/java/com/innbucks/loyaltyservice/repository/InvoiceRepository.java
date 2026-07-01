package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    List<Invoice> findByMerchantIdOrderByPeriodEndDesc(UUID merchantId);
    Page<Invoice> findByMerchantIdOrderByPeriodEndDesc(UUID merchantId, Pageable pageable);
    List<Invoice> findByTenantIdAndStatus(UUID tenantId, Invoice.Status status);
    Optional<Invoice> findByMerchantIdAndPeriodStartAndPeriodEnd(UUID merchantId, LocalDate periodStart, LocalDate periodEnd);
    long countByStatus(Invoice.Status status);
}
