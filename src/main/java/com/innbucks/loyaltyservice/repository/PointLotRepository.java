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

    /** Live lots for a wallet, soonest-to-expire first — the FIFO burn order. */
    @Query("""
        SELECT l FROM PointLot l
        WHERE l.walletId = :walletId AND l.remainingAmount > 0 AND l.expiresAt > :now
        ORDER BY l.expiresAt ASC, l.earnedAt ASC, l.id ASC
        """)
    List<PointLot> findLiveForConsumption(@Param("walletId") UUID walletId, @Param("now") Instant now);

    /** A wallet's lots that have expired but still hold points (to be released). */
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
}
