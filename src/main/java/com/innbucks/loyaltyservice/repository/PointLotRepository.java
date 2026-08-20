package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.PointLot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PointLotRepository extends JpaRepository<PointLot, UUID> {

    /** All lots for a wallet (used by reconciliation and tests). */
    List<PointLot> findByWalletId(UUID walletId);

    /**
     * Live lots for a wallet in FIFO burn order: soonest-to-expire first, then
     * never-expiring lots (expiresAt IS NULL) last.
     *
     * <p>A NULL expiry means the lot never expires, so it must be included here
     * — an explicit {@code IS NULL} branch is required because {@code NULL >
     * :now} is UNKNOWN and would silently drop every non-expiring lot, making
     * the customer's whole balance unspendable.
     *
     * <p>The leading CASE puts expiring lots ahead of non-expiring ones so a
     * redemption spends the points with a deadline first, leaving the ones that
     * keep forever — the outcome that loses the customer the least. It is
     * written out rather than relying on the database's default NULL ordering
     * (Postgres happens to sort NULLs last in ASC, but that is a dialect
     * detail, not something to hang correct burn order on).
     */
    @Query("""
        SELECT l FROM PointLot l
        WHERE l.walletId = :walletId AND l.remainingAmount > 0
          AND (l.expiresAt IS NULL OR l.expiresAt > :now)
        ORDER BY CASE WHEN l.expiresAt IS NULL THEN 1 ELSE 0 END ASC,
                 l.expiresAt ASC, l.earnedAt ASC, l.id ASC
        """)
    List<PointLot> findLiveForConsumption(@Param("walletId") UUID walletId, @Param("now") Instant now);

    /** A wallet's lots that have expired but still hold points (to be released).
     *  Never-expiring lots (expiresAt IS NULL) are excluded for free: {@code NULL
     *  <= :now} is UNKNOWN, never true. That is deliberate, not an oversight —
     *  do not "fix" it by coalescing the NULL to a date. */
    @Query("""
        SELECT l FROM PointLot l
        WHERE l.walletId = :walletId AND l.remainingAmount > 0 AND l.expiresAt <= :now
        """)
    List<PointLot> findDueForExpiry(@Param("walletId") UUID walletId, @Param("now") Instant now);

    /** Distinct wallets that have expired-but-unreleased lots — drives the sweep. */
    @Query("""
        SELECT DISTINCT l.walletId FROM PointLot l
        WHERE l.remainingAmount > 0 AND l.expiresAt <= :now
        """)
    List<UUID> findWalletsWithDueLots(@Param("now") Instant now, Pageable pageable);

    /** Distinct wallets holding live lots that enter the warning window
     *  (expiring after {@code now} but by {@code cutoff}) and were never
     *  warned — drives the daily ExpiryWarningSweeper. Never-expiring lots are
     *  excluded by the same NULL semantics as above, so no customer is ever
     *  nudged about points that have no deadline. */
    @Query("""
        SELECT DISTINCT l.walletId FROM PointLot l
        WHERE l.remainingAmount > 0 AND l.expiryWarnedAt IS NULL
          AND l.expiresAt > :now AND l.expiresAt <= :cutoff
        """)
    List<UUID> findWalletsWithLotsToWarn(@Param("now") Instant now,
                                         @Param("cutoff") Instant cutoff,
                                         Pageable pageable);

    /** One wallet's warnable lots (same window as findWalletsWithLotsToWarn),
     *  soonest-to-expire first. */
    @Query("""
        SELECT l FROM PointLot l
        WHERE l.walletId = :walletId AND l.remainingAmount > 0 AND l.expiryWarnedAt IS NULL
          AND l.expiresAt > :now AND l.expiresAt <= :cutoff
        ORDER BY l.expiresAt ASC
        """)
    List<PointLot> findWarnableLots(@Param("walletId") UUID walletId,
                                    @Param("now") Instant now,
                                    @Param("cutoff") Instant cutoff);
}
