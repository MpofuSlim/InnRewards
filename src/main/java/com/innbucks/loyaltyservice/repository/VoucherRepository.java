package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Voucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

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
}
