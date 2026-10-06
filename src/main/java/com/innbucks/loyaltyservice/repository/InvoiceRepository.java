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

    /** {@link #findByMerchantIdOrderByPeriodEndDesc} for a page of merchants (never an
     *  empty set); the caller groups by merchant, keeping this order within each. */
    List<Invoice> findByMerchantIdInOrderByPeriodEndDesc(java.util.Collection<UUID> merchantIds);

    /**
     * Which of {@code merchantIds} already have an invoice for exactly this
     * period — the invoice run's "already billed" check, asked once per billing
     * period instead of once per merchant. Same predicate as
     * {@link #findByMerchantIdAndPeriodStartAndPeriodEnd}.
     */
    @org.springframework.data.jpa.repository.Query("""
        SELECT DISTINCT i.merchantId FROM Invoice i
        WHERE i.merchantId IN :merchantIds
          AND i.periodStart = :periodStart AND i.periodEnd = :periodEnd
        """)
    List<UUID> findMerchantIdsInvoicedFor(
            @org.springframework.data.repository.query.Param("merchantIds") java.util.Collection<UUID> merchantIds,
            @org.springframework.data.repository.query.Param("periodStart") LocalDate periodStart,
            @org.springframework.data.repository.query.Param("periodEnd") LocalDate periodEnd);
}
