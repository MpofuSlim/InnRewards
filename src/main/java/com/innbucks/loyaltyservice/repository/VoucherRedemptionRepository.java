package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherRedemptionRepository extends JpaRepository<VoucherRedemption, UUID> {
    List<VoucherRedemption> findByVoucherIdOrderByRedeemedAtDesc(UUID voucherId);
    List<VoucherRedemption> findTop100ByMerchantIdOrderByRedeemedAtDesc(UUID merchantId);

    /** Successful USES per voucher for a page of vouchers, so the detailed
     *  report shows redemptionCount without an N+1 per row. Row: [voucherId, count].
     *  REJECTED rows are refusals, not uses, and are not counted (V61). */
    @Query("SELECT r.voucherId, COUNT(r) FROM VoucherRedemption r "
            + "WHERE r.voucherId IN :ids "
            + "AND r.result = com.innbucks.loyaltyservice.entity.VoucherRedemption.Result.SUCCESS "
            + "GROUP BY r.voucherId")
    List<Object[]> countByVoucherIdIn(@Param("ids") List<UUID> ids);

    /**
     * The successful use a redemption's idempotency key already names, if any
     * (V61). One per (merchant, reference) — the partial unique index
     * {@code uq_voucher_redemption_merchant_reference} — so a retry finds the
     * row its first attempt wrote and replays it.
     */
    Optional<VoucherRedemption> findFirstByMerchantIdAndReferenceAndResult(UUID merchantId, String reference,
                                                                           VoucherRedemption.Result result);

    /** Whether the voucher has been used successfully before — false means the
     *  next use is its FIRST, the one a FIXED redeem-side fee is billed on. */
    boolean existsByVoucherIdAndResult(UUID voucherId, VoucherRedemption.Result result);

    // --- Billing and reports read USES, not exhausted vouchers (V61) ----------
    //
    // A FIXED redeem-side fee is charged on a voucher's first use and a
    // PERCENTAGE one on every use's amount (EffectiveFees.feeForRedemptionUse),
    // so these return exactly the three facts that pricing reads. Window is
    // half-open [from, to), so adjacent billing periods never bill one use
    // twice. The per-merchant and the grouped twin share the predicate; the
    // grouped one is never called with an empty set (IN () is not portable SQL).

    /** One merchant's successful uses in [from, to): {@code [redeemedAt, amount, firstUse]}. */
    @Query("""
        SELECT r.redeemedAt, r.amount, r.firstUse FROM VoucherRedemption r
        WHERE r.merchantId = :merchantId
          AND r.result = com.innbucks.loyaltyservice.entity.VoucherRedemption.Result.SUCCESS
          AND r.redeemedAt >= :from AND r.redeemedAt < :to
        """)
    List<Object[]> usesBetween(@Param("merchantId") UUID merchantId,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    /** {@link #usesBetween} for several merchants: {@code [merchantId, redeemedAt, amount, firstUse]}. */
    @Query("""
        SELECT r.merchantId, r.redeemedAt, r.amount, r.firstUse FROM VoucherRedemption r
        WHERE r.merchantId IN :merchantIds
          AND r.result = com.innbucks.loyaltyservice.entity.VoucherRedemption.Result.SUCCESS
          AND r.redeemedAt >= :from AND r.redeemedAt < :to
        """)
    List<Object[]> usesBetweenForMerchants(@Param("merchantIds") Collection<UUID> merchantIds,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to);
}
