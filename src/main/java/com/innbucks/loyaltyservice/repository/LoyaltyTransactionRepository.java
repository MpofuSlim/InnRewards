package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, UUID> {

    Optional<LoyaltyTransaction> findFirstByMerchantIdAndReference(UUID merchantId, String reference);

    /**
     * Pessimistic-write lock on a single transaction row. Used by
     * {@code TransactionService.reverse} so two concurrent reversals of the same
     * original serialize: the first holds the lock, flips status to REVERSED and
     * commits; the second blocks, then re-reads status=REVERSED and is rejected
     * with ALREADY_REVERSED instead of inserting a second compensating credit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM LoyaltyTransaction t WHERE t.id = :id")
    Optional<LoyaltyTransaction> lockById(@Param("id") UUID id);

    List<LoyaltyTransaction> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<LoyaltyTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<LoyaltyTransaction> findByTenantIdAndShopIdOrderByCreatedAtDesc(UUID tenantId, UUID shopId, Pageable pageable);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.pointsDelta > 0 THEN t.pointsDelta ELSE 0 END), 0)
        FROM LoyaltyTransaction t
        WHERE t.merchantId = :merchantId
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        """)
    BigDecimal sumPointsIssued(@Param("merchantId") UUID merchantId,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.pointsDelta < 0 THEN -t.pointsDelta ELSE 0 END), 0)
        FROM LoyaltyTransaction t
        WHERE t.merchantId = :merchantId
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        """)
    BigDecimal sumPointsRedeemed(@Param("merchantId") UUID merchantId,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.pointsDelta > 0 THEN t.pointsDelta ELSE 0 END), 0)
        FROM LoyaltyTransaction t
        WHERE t.userId = :userId
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        """)
    BigDecimal sumPointsIssuedByUser(@Param("userId") UUID userId,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.pointsDelta < 0 THEN -t.pointsDelta ELSE 0 END), 0)
        FROM LoyaltyTransaction t
        WHERE t.userId = :userId
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        """)
    BigDecimal sumPointsRedeemedByUser(@Param("userId") UUID userId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.pointsDelta > 0 THEN t.pointsDelta ELSE 0 END), 0)
        FROM LoyaltyTransaction t
        WHERE t.tenantId = :tenantId AND t.shopId = :shopId
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        """)
    BigDecimal sumPointsIssuedByShop(@Param("tenantId") UUID tenantId,
                                     @Param("shopId") UUID shopId,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.pointsDelta < 0 THEN -t.pointsDelta ELSE 0 END), 0)
        FROM LoyaltyTransaction t
        WHERE t.tenantId = :tenantId AND t.shopId = :shopId
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        """)
    BigDecimal sumPointsRedeemedByShop(@Param("tenantId") UUID tenantId,
                                       @Param("shopId") UUID shopId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    /**
     * Per-customer (phone) points breakdown for a shop over a period. Transactions
     * store the customer's {@code userId}, not their phone, so this theta-joins to
     * {@code LoyaltyUser}. Row shape: [String phoneNumber, BigDecimal issued,
     * BigDecimal redeemed, long count]. Ordering is left to the caller.
     */
    @Query("""
        SELECT u.phoneNumber,
               COALESCE(SUM(CASE WHEN t.pointsDelta > 0 THEN t.pointsDelta ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN t.pointsDelta < 0 THEN -t.pointsDelta ELSE 0 END), 0),
               COUNT(t)
        FROM LoyaltyTransaction t, LoyaltyUser u
        WHERE u.id = t.userId
          AND t.tenantId = :tenantId AND t.shopId = :shopId
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        GROUP BY u.phoneNumber
        """)
    List<Object[]> pointsByPhoneForShop(@Param("tenantId") UUID tenantId,
                                        @Param("shopId") UUID shopId,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to);

    /**
     * Net points originated by a tenant (issued minus redeemed), all-time, from
     * the ledger. Wallets are global per customer now, so per-tenant outstanding
     * can no longer come from wallet balances — it's derived here from the
     * tenant's transaction attribution, which is preserved on every row.
     */
    @Query("""
        SELECT COALESCE(SUM(t.pointsDelta), 0)
        FROM LoyaltyTransaction t
        WHERE t.tenantId = :tenantId
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        """)
    BigDecimal sumNetPointsByTenant(@Param("tenantId") UUID tenantId);

    long countByMerchantIdAndCreatedAtBetween(UUID merchantId, Instant from, Instant to);

    long countByUserIdAndCreatedAtBetween(UUID userId, Instant from, Instant to);

    long countByTenantIdAndShopIdAndCreatedAtBetween(UUID tenantId, UUID shopId, Instant from, Instant to);

    /**
     * Type + count + issued + redeemed in one query. Powers the "points by
     * type" report — operators see "PURCHASE: 1842 txns issuing 184k pts"
     * in a single row instead of running the existing count-only
     * {@link #countByType} alongside two sum queries per type.
     */
    @Query("""
        SELECT t.type,
               COUNT(t),
               COALESCE(SUM(CASE WHEN t.pointsDelta > 0 THEN t.pointsDelta ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN t.pointsDelta < 0 THEN -t.pointsDelta ELSE 0 END), 0)
        FROM LoyaltyTransaction t
        WHERE t.tenantId = :tenantId
          AND (:merchantId IS NULL OR t.merchantId = :merchantId)
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        GROUP BY t.type
        """)
    List<Object[]> sumPointsByType(@Param("tenantId") UUID tenantId,
                                   @Param("merchantId") UUID merchantId,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to);

    /**
     * Daily buckets for time-series charts. `date_trunc` runs identically
     * in Postgres and H2's PostgreSQL-compat mode (used by the unit
     * suite). Returns one row per UTC day in [from, to), even days with
     * activity that nets to zero — but skips days with no rows, so the
     * caller has to backfill empty buckets if they want a contiguous
     * series. Doing the backfill in Java keeps this query single-purpose.
     */
    @Query(value = """
        SELECT DATE_TRUNC('day', t.created_at) AS bucket,
               COALESCE(SUM(CASE WHEN t.points_delta > 0 THEN t.points_delta ELSE 0 END), 0) AS issued,
               COALESCE(SUM(CASE WHEN t.points_delta < 0 THEN -t.points_delta ELSE 0 END), 0) AS redeemed,
               COUNT(*) AS txn_count
        FROM loyalty_transactions t
        WHERE t.tenant_id = :tenantId
          AND (CAST(:merchantId AS VARCHAR) IS NULL OR t.merchant_id = :merchantId)
          AND t.created_at >= :from AND t.created_at < :to
          AND t.status = 'POSTED'
        GROUP BY DATE_TRUNC('day', t.created_at)
        ORDER BY bucket
        """, nativeQuery = true)
    List<Object[]> dailyPointBuckets(@Param("tenantId") UUID tenantId,
                                     @Param("merchantId") UUID merchantId,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to);

    /**
     * Range scan for CSV export. Sorted by createdAt so the file is
     * deterministic and operators can resume / diff. Paged because a
     * busy tenant's month of transactions can be hundreds of thousands
     * of rows; the export controller streams pages rather than holding
     * everything in memory at once.
     */
    Page<LoyaltyTransaction> findByTenantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            UUID tenantId, Instant from, Instant to, Pageable pageable);

    Page<LoyaltyTransaction> findByTenantIdAndMerchantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            UUID tenantId, UUID merchantId, Instant from, Instant to, Pageable pageable);

    @Query("""
        SELECT t.type, COUNT(t)
        FROM LoyaltyTransaction t
        WHERE t.tenantId = :tenantId
          AND (:merchantId IS NULL OR t.merchantId = :merchantId)
          AND t.createdAt >= :from AND t.createdAt < :to
        GROUP BY t.type
        """)
    List<Object[]> countByType(@Param("tenantId") UUID tenantId,
                               @Param("merchantId") UUID merchantId,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    @Query("""
        SELECT COUNT(t) FROM LoyaltyTransaction t
        WHERE t.createdAt >= :from
        """)
    long countSince(@Param("from") Instant from);

    long countByCampaignId(UUID campaignId);
}
