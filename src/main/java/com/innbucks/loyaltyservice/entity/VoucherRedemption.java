package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "voucher_redemptions", indexes = {
        @Index(name = "idx_redemption_voucher", columnList = "voucher_id"),
        @Index(name = "idx_redemption_user", columnList = "user_id"),
        @Index(name = "idx_redemption_merchant", columnList = "merchant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class VoucherRedemption {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "voucher_id", nullable = false)
    private UUID voucherId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "outlet_code", length = 80)
    private String outletCode;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result result = Result.SUCCESS;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "device_fingerprint", length = 128)
    private String deviceFingerprint;

    @Column(length = 200)
    private String reason;

    /**
     * Money this use took off the bill, in the voucher's currency (V61). The
     * requested amount, capped at what the voucher had left. Null on REJECTED
     * rows, and on a legacy voucher with no money face value.
     */
    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    /**
     * The USD share of the voucher's issue-time liability ({@code
     * vouchers.base_value}) this use consumed. Telescoped, never re-converted:
     * a voucher's uses always sum to exactly its base value — see
     * {@code VoucherService.baseShare}. Null when the voucher has no base value.
     */
    @Column(name = "base_amount", precision = 19, scale = 4)
    private BigDecimal baseAmount;

    /** The voucher's remaining balance once this use landed. Null on legacy and REJECTED rows. */
    @Column(name = "balance_after", precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    /**
     * The caller's idempotency key for this use. One SUCCESS row per
     * (merchant, reference) — a partial unique index (V61). A retry with the
     * same reference replays this row instead of spending again.
     */
    @Column(length = 100)
    private String reference;

    /**
     * True on the voucher's first SUCCESSFUL use. A FIXED redeem-side fee is
     * billed on the first use only; a PERCENTAGE one on every use's amount.
     */
    @Column(name = "first_use", nullable = false)
    private boolean firstUse;

    // ----- partner redemptions (V62) -----

    /** The partner whose platform redeemed it; null for every other surface. */
    @Column(name = "partner_id")
    private UUID partnerId;

    /** The customer's account id on the partner's platform. */
    @Column(name = "end_user_ref", length = 100)
    private String endUserRef;

    @Column(length = 8)
    private String channel;

    @Column(name = "terminal_id", length = 64)
    private String terminalId;

    /** Payment of this use's amount from holding to the merchant: PENDING,
     *  SETTLED or ATTENTION (an operator must resolve it). Null off-partner. */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", length = 16)
    private SettlementStatus settlementStatus;

    @Column(name = "settlement_ref", length = 100)
    private String settlementRef;

    @Column(name = "settled_at")
    private Instant settledAt;

    public enum Result { SUCCESS, REJECTED }

    /** The CHECK {@code chk_voucher_redemptions_settlement} lists every value. */
    public enum SettlementStatus { PENDING, SETTLED, ATTENTION }
}
