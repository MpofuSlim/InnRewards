package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Voucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// JpaSpecificationExecutor powers the detailed voucher reports: one type-safe,
// null-aware filter (tenant / exclude-tenant / merchant / shop / status / date)
// drives the operator, tenant, merchant and shop views without a combinatorial
// explosion of derived-query methods (and without the nullable-enum-in-JPQL
// footgun). See ReportingService.voucherReport / voucherCsv.
public interface VoucherRepository extends JpaRepository<Voucher, UUID>,
        JpaSpecificationExecutor<Voucher> {

    Optional<Voucher> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Voucher v WHERE v.code = :code")
    Optional<Voucher> lockByCode(@Param("code") String code);

    List<Voucher> findByAssignedUserIdAndStatusIn(UUID userId, List<Voucher.Status> statuses);

    Page<Voucher> findByAssignedUserIdAndStatusIn(UUID userId, List<Voucher.Status> statuses, Pageable pageable);

    // Multi-user variant — used by /loyalty/vouchers/users/by-phone/{phone}/active
    // to aggregate every voucher for a phone across every tenant projection in
    // one query (a phone can have N LoyaltyUsers, one per tenant).
    Page<Voucher> findByAssignedUserIdInAndStatusIn(List<UUID> userIds,
                                                   List<Voucher.Status> statuses,
                                                   Pageable pageable);

    List<Voucher> findByTenantIdAndStatus(UUID tenantId, Voucher.Status status);

    Page<Voucher> findByTenantIdAndStatus(UUID tenantId, Voucher.Status status, Pageable pageable);

    @Query("SELECT v FROM Voucher v WHERE v.expiresAt IS NOT NULL AND v.expiresAt < :now AND v.status NOT IN " +
            "(com.innbucks.loyaltyservice.entity.Voucher.Status.REDEEMED, " +
            " com.innbucks.loyaltyservice.entity.Voucher.Status.EXPIRED, " +
            " com.innbucks.loyaltyservice.entity.Voucher.Status.REVOKED)")
    List<Voucher> findExpired(@Param("now") Instant now);

    long countByMerchantIdAndIssuedAtBetween(UUID merchantId, Instant from, Instant to);

    long countByMerchantIdAndRedeemedAtBetween(UUID merchantId, Instant from, Instant to);

    // Per-merchant voucher pulls used by InvoicingService + ReportingService to
    // compute per-voucher fees under the merchant's 3-mode fee model. The
    // PERCENTAGE / FIXED_PLUS_PERCENTAGE legs need each voucher's face value,
    // so a COUNT(*) is no longer enough. The result is bounded by per-merchant
    // per-period activity (typically <10k rows / month per merchant); no
    // pagination required at this scale.
    List<Voucher> findByMerchantIdAndIssuedAtBetween(UUID merchantId, Instant from, Instant to);

    List<Voucher> findByMerchantIdAndRedeemedAtBetween(UUID merchantId, Instant from, Instant to);

    long countByTenantIdAndStatus(UUID tenantId, Voucher.Status status);

    // One-query active-voucher count grouped by user. Powers /me/wallet so we
    // don't issue N separate findByAssignedUserIdAndStatusIn calls for a
    // customer who's enrolled in N tenants.
    @Query("SELECT v.assignedUserId, COUNT(v) FROM Voucher v " +
            "WHERE v.assignedUserId IN :userIds " +
            "AND v.status IN (com.innbucks.loyaltyservice.entity.Voucher.Status.ISSUED, " +
            "                 com.innbucks.loyaltyservice.entity.Voucher.Status.DELIVERED, " +
            "                 com.innbucks.loyaltyservice.entity.Voucher.Status.VIEWED, " +
            "                 com.innbucks.loyaltyservice.entity.Voucher.Status.PARTIALLY_USED) " +
            "GROUP BY v.assignedUserId")
    List<Object[]> countActiveGroupedByUserId(@Param("userIds") List<UUID> userIds);

    /**
     * Per-status count + summed face value for the detailed voucher reports'
     * header block, over a scope selected by nullable filters. Every filter is a
     * nullable UUID so one query serves all levels: operator (excludeTenantId =
     * the internal ticketing tenant, everything else null), tenant, merchant and
     * shop. Status is NOT a filter here — the report always shows the full status
     * breakdown for its scope. Row shape: [Voucher.Status status, long count,
     * BigDecimal faceValueSum]. Bounded to {@code issuedAt} in [from, to).
     */
    @Query("""
        SELECT v.status, COUNT(v), COALESCE(SUM(v.value), 0)
        FROM Voucher v
        WHERE (:tenantId IS NULL OR v.tenantId = :tenantId)
          AND (:excludeTenantId IS NULL OR v.tenantId <> :excludeTenantId)
          AND (:merchantId IS NULL OR v.merchantId = :merchantId)
          AND (:shopId IS NULL OR v.shopId = :shopId)
          AND v.issuedAt >= :from AND v.issuedAt < :to
        GROUP BY v.status
        """)
    List<Object[]> reportSummaryByStatus(@Param("tenantId") UUID tenantId,
                                         @Param("excludeTenantId") UUID excludeTenantId,
                                         @Param("merchantId") UUID merchantId,
                                         @Param("shopId") UUID shopId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

    /**
     * Total face value the merchant's customers have actually redeemed (fully or
     * partially — {@code redeemedAt} is stamped on both). Powers the merchant-360
     * report's voucher block; the issued-side value comes from
     * {@link #reportSummaryByStatus} so it isn't duplicated here.
     */
    @Query("""
        SELECT COALESCE(SUM(v.value), 0) FROM Voucher v
        WHERE v.merchantId = :merchantId AND v.redeemedAt IS NOT NULL
        """)
    BigDecimal sumRedeemedValueByMerchantId(@Param("merchantId") UUID merchantId);

    // Merchant-360 report: outstanding vouchers that will lapse inside the
    // window. The caller passes the live statuses (ISSUED/DELIVERED/VIEWED/
    // PARTIALLY_USED) — redeemed/expired/revoked ones can't "expire soon".
    long countByMerchantIdAndExpiresAtBetweenAndStatusIn(UUID merchantId, Instant from, Instant to,
                                                         Collection<Voucher.Status> statuses);
}
