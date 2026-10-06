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
public interface VoucherRepository extends VoucherReportQueries, JpaRepository<Voucher, UUID>,
        JpaSpecificationExecutor<Voucher> {

    Optional<Voucher> findByCode(String code);

    /**
     * Which of {@code codes} already exist — ONE query for a whole bulk batch.
     * Bulk issue used to call {@link #findByCode} once per voucher, and each of
     * those auto-flushed and dirty-checked every voucher created so far in the
     * transaction, so a batch got quadratically slower. Returns codes only, so
     * nothing is loaded into the persistence context.
     */
    @Query("select v.code from Voucher v where v.code in :codes")
    List<String> findExistingCodes(@Param("codes") Collection<String> codes);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Voucher v WHERE v.code = :code")
    Optional<Voucher> lockByCode(@Param("code") String code);

    /**
     * Moves one voucher to EXPIRED, but only if it is genuinely past its
     * deadline and still live. Called by
     * {@code VoucherRedemptionAuditWriter} after a redemption was refused as
     * expired — the controller's Swagger promises that flip, and the refusing
     * transaction cannot make it itself (it holds the row's write lock; see
     * {@code VoucherRedemptionRejectedEvent}).
     *
     * <p>Both halves of the predicate are guards, not filters. {@code expiresAt}
     * re-checks the deadline in the database so this can never write a status
     * that was not already true, and the live-status list means a REDEEMED or
     * REVOKED transition that landed in between is never clobbered. That makes
     * the call idempotent and safe to lose.
     *
     * <p>NOTE: the status list is {@code Voucher.LIVE_STATUSES} spelled out — a
     * query string cannot reference the constant, so the two change together.
     * Pinned by {@code VoucherLiveStatusJpqlTest}.
     *
     * @return rows updated: 1 when it moved, 0 when another writer got there
     *         first or the deadline has not actually passed.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE Voucher v SET v.status = com.innbucks.loyaltyservice.entity.Voucher.Status.EXPIRED
        WHERE v.id = :id
          AND v.expiresAt IS NOT NULL AND v.expiresAt <= CURRENT_TIMESTAMP
          AND v.status IN (com.innbucks.loyaltyservice.entity.Voucher.Status.ISSUED,
                           com.innbucks.loyaltyservice.entity.Voucher.Status.VIEWED,
                           com.innbucks.loyaltyservice.entity.Voucher.Status.PARTIALLY_USED)
        """)
    int markExpiredIfDue(@Param("id") UUID id);

    /**
     * Pessimistic-write lock on one voucher by id. Used by
     * {@code VoucherService.transfer} so two concurrent transfers of the same
     * voucher serialize: the first holds the lock, stamps {@code transferredAt}
     * and commits; the second blocks, then re-reads a non-null
     * {@code transferredAt} and is refused as a second hop. Without the lock
     * both readers could see {@code transferredAt == null} and the @Version
     * check would be the only thing between them and a double-transfer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Voucher v WHERE v.id = :id")
    Optional<Voucher> lockById(@Param("id") UUID id);

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

    Page<Voucher> findByTenantIdAndMerchantIdInAndStatus(UUID tenantId, java.util.Collection<UUID> merchantIds,
                                                         Voucher.Status status, Pageable pageable);

    @Query("SELECT v FROM Voucher v WHERE v.expiresAt IS NOT NULL AND v.expiresAt < :now AND v.status NOT IN " +
            "(com.innbucks.loyaltyservice.entity.Voucher.Status.REDEEMED, " +
            " com.innbucks.loyaltyservice.entity.Voucher.Status.EXPIRED, " +
            " com.innbucks.loyaltyservice.entity.Voucher.Status.REVOKED)")
    List<Voucher> findExpired(@Param("now") Instant now);

    /**
     * How many vouchers {@link #findExpired} would return, without loading them.
     * Same predicate, so the operator dashboard's expiring counts are unchanged;
     * it used to materialise every such voucher on the platform to call
     * {@code size()}.
     */
    @Query("SELECT COUNT(v) FROM Voucher v WHERE v.expiresAt IS NOT NULL AND v.expiresAt < :now AND v.status NOT IN " +
            "(com.innbucks.loyaltyservice.entity.Voucher.Status.REDEEMED, " +
            " com.innbucks.loyaltyservice.entity.Voucher.Status.EXPIRED, " +
            " com.innbucks.loyaltyservice.entity.Voucher.Status.REVOKED)")
    long countExpired(@Param("now") Instant now);

    long countByMerchantIdAndIssuedAtBetween(UUID merchantId, Instant from, Instant to);

    long countByMerchantIdAndRedeemedAtBetween(UUID merchantId, Instant from, Instant to);

    // --- Grouped twins of the per-merchant voucher reads, for the reports and
    // the invoice run that used to call them once per merchant. Each keeps its
    // twin's predicate (BETWEEN stays BETWEEN — inclusive, as the derived
    // queries are) and only replaces the one merchant with a set or a
    // sub-select. Never called with an empty set: IN () is not portable SQL.

    /** {@link #countByMerchantIdAndIssuedAtBetween}, summed over every merchant
     *  whose tenant is NOT {@code excludedTenantId} (the operator dashboard). */
    @Query("""
        SELECT COUNT(v) FROM Voucher v
        WHERE v.merchantId IN (SELECT m.id FROM Merchant m WHERE m.tenantId <> :excludedTenantId)
          AND v.issuedAt BETWEEN :from AND :to
        """)
    long countIssuedBetweenExcludingTenant(@Param("excludedTenantId") UUID excludedTenantId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to);

    /** {@link #countByMerchantIdAndRedeemedAtBetween}, summed over every merchant
     *  whose tenant is NOT {@code excludedTenantId} (the operator dashboard). */
    @Query("""
        SELECT COUNT(v) FROM Voucher v
        WHERE v.merchantId IN (SELECT m.id FROM Merchant m WHERE m.tenantId <> :excludedTenantId)
          AND v.redeemedAt BETWEEN :from AND :to
        """)
    long countRedeemedBetweenExcludingTenant(@Param("excludedTenantId") UUID excludedTenantId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    /** {@link #countByMerchantIdAndIssuedAtBetween} per merchant: {@code [merchantId, count]}. */
    @Query("""
        SELECT v.merchantId, COUNT(v) FROM Voucher v
        WHERE v.merchantId IN :merchantIds AND v.issuedAt BETWEEN :from AND :to
        GROUP BY v.merchantId
        """)
    List<Object[]> countIssuedBetweenByMerchant(@Param("merchantIds") Collection<UUID> merchantIds,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    /** {@link #countByMerchantIdAndRedeemedAtBetween} per merchant: {@code [merchantId, count]}. */
    @Query("""
        SELECT v.merchantId, COUNT(v) FROM Voucher v
        WHERE v.merchantId IN :merchantIds AND v.redeemedAt BETWEEN :from AND :to
        GROUP BY v.merchantId
        """)
    List<Object[]> countRedeemedBetweenByMerchant(@Param("merchantIds") Collection<UUID> merchantIds,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to);

    /**
     * The rows {@link #findByMerchantIdAndIssuedAtBetween} returns, for several
     * merchants, as {@code [merchantId, issuedAt, faceValue]} — the only fields
     * fee pricing reads ({@code EffectiveFees#feeForIssuedFaceValue}), so a
     * period's vouchers are not loaded as entities. {@code faceValue} is
     * {@code value}, the per-voucher amount a PERCENTAGE fee multiplies; it is
     * never summed across vouchers here.
     */
    @Query("""
        SELECT v.merchantId, v.issuedAt, v.value FROM Voucher v
        WHERE v.merchantId IN :merchantIds AND v.issuedAt BETWEEN :from AND :to
        """)
    List<Object[]> issuedFaceValuesBetween(@Param("merchantIds") Collection<UUID> merchantIds,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to);

    /** {@link #findByMerchantIdAndRedeemedAtBetween} for several merchants, as
     *  {@code [merchantId, redeemedAt, faceValue]} — see {@link #issuedFaceValuesBetween}. */
    @Query("""
        SELECT v.merchantId, v.redeemedAt, v.value FROM Voucher v
        WHERE v.merchantId IN :merchantIds AND v.redeemedAt BETWEEN :from AND :to
        """)
    List<Object[]> redeemedFaceValuesBetween(@Param("merchantIds") Collection<UUID> merchantIds,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

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
    // NOTE: this IN list is Voucher.LIVE_STATUSES spelled out — a query string
    // cannot reference the constant, so the two must be changed together. It
    // dropped DELIVERED in V48 when that status was merged into ISSUED.
    @Query("SELECT v.assignedUserId, COUNT(v) FROM Voucher v " +
            "WHERE v.assignedUserId IN :userIds " +
            "AND v.status IN (com.innbucks.loyaltyservice.entity.Voucher.Status.ISSUED, " +
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
     * BigDecimal baseValueSum]. Bounded to {@code issuedAt} in [from, to).
     *
     * <p><b>The money column is {@code baseValue} (USD), not {@code value}</b>
     * (multi-currency, V38). Summing {@code value} across a scope that mixes
     * currencies adds ZWG to USD and yields a number that is not money in any
     * currency. {@code baseValue} is the issue-time USD figure, so every row in
     * the sum is in one unit.
     *
     * <p>Consequence: rows with a NULL {@code baseValue} drop out of the sum
     * (SQL SUM ignores NULL) while still being COUNTed. That is deliberate and
     * correct — those are the vouchers with no money face value (PERCENT /
     * FREE_ITEM / COMBO), which never belonged in a money total: a "10% off"
     * voucher was previously contributing a literal 10 to it.
     */
    @Query("""
        SELECT v.status, COUNT(v), COALESCE(SUM(v.baseValue), 0)
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

    /** {@link #reportSummaryByStatus} over a set of merchants in one tenant
     *  (never an empty set — the caller answers "nothing" itself). */
    @Query("""
        SELECT v.status, COUNT(v), COALESCE(SUM(v.baseValue), 0)
        FROM Voucher v
        WHERE v.tenantId = :tenantId
          AND v.merchantId IN :merchantIds
          AND v.issuedAt >= :from AND v.issuedAt < :to
        GROUP BY v.status
        """)
    List<Object[]> reportSummaryByStatusForMerchants(@Param("tenantId") UUID tenantId,
                                                     @Param("merchantIds") java.util.Collection<UUID> merchantIds,
                                                     @Param("from") Instant from,
                                                     @Param("to") Instant to);

    /** How each voucher was paid for: {@code [voucherId, orderRef, paidVia, paymentRail]}
     *  per PAID purchase order among {@code voucherIds}. A voucher with no row
     *  was issued without a payment. */
    @Query("""
        SELECT o.voucherId, o.orderRef, o.paidVia, o.paymentRail
        FROM VoucherPurchaseOrder o
        WHERE o.voucherId IN :voucherIds
        """)
    List<Object[]> purchaseInfoForVouchers(@Param("voucherIds") java.util.Collection<UUID> voucherIds);

    long countByTenantIdAndMerchantIdInAndStatus(UUID tenantId, java.util.Collection<UUID> merchantIds,
                                                 Voucher.Status status);

    /**
     * Total USD value of the merchant's FULLY redeemed vouchers. Powers the
     * merchant-360 report's voucher block; the issued-side value comes from
     * {@link #reportSummaryByStatus} so it isn't duplicated here.
     *
     * <p><b>Fully, not partially</b> — this javadoc used to claim "(fully or
     * partially — {@code redeemedAt} is stamped on both)" and that was never
     * true: {@code VoucherService.doRedeem} stamps {@code redeemedAt} only in the
     * exhaustion branch, so a MULTI_USE voucher with four of five uses spent
     * contributes zero here. The same premise reaches
     * {@code InvoicingService.generate} through
     * {@link #findByMerchantIdAndRedeemedAtBetween}, which is why a partially
     * used voucher is also never billed a redeem-side fee. Whether that is the
     * wanted commercial rule is an open question for the platform owner; what is
     * fixed here is the description, so nobody reads a number as something it is
     * not.
     *
     * <p>Sums {@code baseValue}, not {@code value}, for the same reason as
     * {@link #reportSummaryByStatus}: one unit per sum, and no percentages
     * masquerading as money.
     */
    @Query("""
        SELECT COALESCE(SUM(v.baseValue), 0) FROM Voucher v
        WHERE v.merchantId = :merchantId AND v.redeemedAt IS NOT NULL
        """)
    BigDecimal sumRedeemedValueByMerchantId(@Param("merchantId") UUID merchantId);

    // Merchant-360 report: outstanding vouchers that will lapse inside the
    // window. The caller passes Voucher.LIVE_STATUSES — redeemed/expired/
    // revoked ones can't "expire soon".
    long countByMerchantIdAndExpiresAtBetweenAndStatusIn(UUID merchantId, Instant from, Instant to,
                                                         Collection<Voucher.Status> statuses);

    /** {@link #reportSummaryByStatus} for the merchant-360 page: one tenant-less,
     *  shop-less summary per merchant, {@code [merchantId, status, count,
     *  baseValueSum]}. Sums {@code baseValue} for the same reason. */
    @Query("""
        SELECT v.merchantId, v.status, COUNT(v), COALESCE(SUM(v.baseValue), 0)
        FROM Voucher v
        WHERE v.merchantId IN :merchantIds
          AND v.issuedAt >= :from AND v.issuedAt < :to
        GROUP BY v.merchantId, v.status
        """)
    List<Object[]> summaryByMerchantAndStatus(@Param("merchantIds") Collection<UUID> merchantIds,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    /** {@link #sumRedeemedValueByMerchantId} per merchant: {@code [merchantId,
     *  baseValueSum]}. Sums {@code baseValue}, never the local {@code value}. */
    @Query("""
        SELECT v.merchantId, COALESCE(SUM(v.baseValue), 0) FROM Voucher v
        WHERE v.merchantId IN :merchantIds AND v.redeemedAt IS NOT NULL
        GROUP BY v.merchantId
        """)
    List<Object[]> sumRedeemedValueByMerchantIds(@Param("merchantIds") Collection<UUID> merchantIds);

    /** {@link #countByMerchantIdAndExpiresAtBetweenAndStatusIn} per merchant:
     *  {@code [merchantId, count]}. Pass {@link Voucher#LIVE_STATUSES}. */
    @Query("""
        SELECT v.merchantId, COUNT(v) FROM Voucher v
        WHERE v.merchantId IN :merchantIds
          AND v.expiresAt BETWEEN :from AND :to
          AND v.status IN :statuses
        GROUP BY v.merchantId
        """)
    List<Object[]> countExpiringBetweenByMerchant(@Param("merchantIds") Collection<UUID> merchantIds,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to,
                                                  @Param("statuses") Collection<Voucher.Status> statuses);

    /** Unredeemed vouchers entering the warning window (expiring after
     *  {@code now} but by {@code cutoff}) that were never warned and have a
     *  reachable assignee — drives the daily ExpiryWarningSweeper. */
    @Query("""
        SELECT v FROM Voucher v
        WHERE v.expiresAt IS NOT NULL AND v.expiryWarnedAt IS NULL
          AND v.expiresAt > :now AND v.expiresAt <= :cutoff
          AND v.status IN (com.innbucks.loyaltyservice.entity.Voucher.Status.ISSUED,
                           com.innbucks.loyaltyservice.entity.Voucher.Status.VIEWED,
                           com.innbucks.loyaltyservice.entity.Voucher.Status.PARTIALLY_USED)
        ORDER BY v.expiresAt ASC
        """)
    List<Voucher> findExpiringForWarning(@Param("now") Instant now,
                                         @Param("cutoff") Instant cutoff,
                                         Pageable pageable);

    // ---- Phone finders for the customer-support 360 (V55 indexes) ----
    //
    // A phone can sit on a voucher in three roles. HELD mirrors
    // VoucherService.holderPhone's precedence exactly: the assignee phone when
    // there is one, else the assigned account's — so the voucher support shows
    // as "held" is the voucher that customer would be allowed to redeem. `phones`
    // is every stored spelling of one number (sender and some assignee columns
    // were written as typed); `holderAccounts` is the phone's projection ids and
    // must not be empty (JPQL IN () is not valid SQL — callers pass a sentinel).

    String HELD_BY_PHONE = """
            (v.assigneePhone IN :phones
             OR ((v.assigneePhone IS NULL OR TRIM(v.assigneePhone) = '')
                 AND v.assignedUserId IN :holderAccounts))""";

    @Query(value = "SELECT v FROM Voucher v WHERE " + HELD_BY_PHONE + " ORDER BY v.issuedAt DESC, v.id",
            countQuery = "SELECT COUNT(v) FROM Voucher v WHERE " + HELD_BY_PHONE)
    Page<Voucher> findHeldByPhone(@Param("phones") Collection<String> phones,
                                  @Param("holderAccounts") Collection<UUID> holderAccounts,
                                  Pageable pageable);

    @Query("SELECT COUNT(v) FROM Voucher v WHERE " + HELD_BY_PHONE)
    long countHeldByPhone(@Param("phones") Collection<String> phones,
                          @Param("holderAccounts") Collection<UUID> holderAccounts);

    /** Held AND in one of {@code statuses} — pass {@link Voucher#LIVE_STATUSES}, never a hand-spelled list. */
    @Query("SELECT COUNT(v) FROM Voucher v WHERE " + HELD_BY_PHONE + " AND v.status IN :statuses")
    long countHeldByPhoneInStatus(@Param("phones") Collection<String> phones,
                                  @Param("holderAccounts") Collection<UUID> holderAccounts,
                                  @Param("statuses") Collection<Voucher.Status> statuses);

    /** Vouchers this phone GIFTED (V46 sender identity). */
    Page<Voucher> findBySenderPhoneInOrderByIssuedAtDesc(Collection<String> phones, Pageable pageable);

    long countBySenderPhoneIn(Collection<String> phones);

    /** Vouchers this phone TRANSFERRED AWAY (V34 single-hop transfer). */
    Page<Voucher> findByTransferredFromPhoneInOrderByTransferredAtDesc(Collection<String> phones,
                                                                      Pageable pageable);

    long countByTransferredFromPhoneIn(Collection<String> phones);

    /** Whether the phone appears on any voucher in any role — part of "is this customer on record". */
    boolean existsByAssigneePhoneInOrSenderPhoneInOrTransferredFromPhoneIn(Collection<String> assignee,
                                                                          Collection<String> sender,
                                                                          Collection<String> transferredFrom);
}
