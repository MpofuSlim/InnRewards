package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vouchers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_voucher_code", columnNames = "code")
}, indexes = {
        @Index(name = "idx_voucher_tenant", columnList = "tenant_id"),
        @Index(name = "idx_voucher_assignee", columnList = "assigned_user_id"),
        @Index(name = "idx_voucher_status", columnList = "status"),
        @Index(name = "idx_voucher_expires_at", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Voucher {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    // The specific outlet the voucher was issued from, captured from the
    // issuing staff member's JWT shop scope (SHOP_ADMIN / SHOP_USER). Null for
    // merchant-level issuance (MERCHANT_ADMIN tokens carry no shop) and for
    // vouchers issued before shop attribution landed. Powers shop-level reports.
    @Column(name = "shop_id")
    private UUID shopId;

    /**
     * The retired template this voucher was minted from — pre-V45 rows only.
     * Templates are gone as a concept; vouchers are issued directly with a
     * type, value and currency. NULL on every voucher issued after V45. Kept
     * because it still feeds the legacy signature payload (see
     * {@code VoucherService.signPayload}) and legacy report name lookups.
     */
    @Column(name = "template_id")
    private UUID templateId;

    /**
     * SINGLE_USE or MULTI_USE — stamped at issue (V45), now that no template
     * carries it. NULL only on pre-V45 rows the backfill could not resolve.
     * The redemption mechanics ride {@link #usesRemaining} as they always
     * did; this is the declared shape, kept for reporting and the client.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", length = 20)
    private VoucherType voucherType;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String signature;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @Column(name = "assignee_phone", length = 32)
    private String assigneePhone;

    @Column(name = "assignee_name", length = 200)
    private String assigneeName;

    // Who issued this voucher — captured at issue time from the authenticated
    // caller's JWT so reports can show a real issuer number (E.164) alongside
    // the receiver. All nullable: internal/system issuance and pre-migration
    // rows have no issuer.
    @Column(name = "issuer_user_id")
    private UUID issuerUserId;

    @Column(name = "issuer_phone", length = 32)
    private String issuerPhone;

    @Column(name = "issuer_email", length = 200)
    private String issuerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ISSUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_channel", length = 20)
    private DeliveryChannel deliveryChannel;

    // The voucher's face value, frozen at issuance — like an invoice line that
    // captures the price at the moment of sale. Since V45 this is ALWAYS a
    // money amount in {@link #currency}: value types (PERCENT / FREE_ITEM /
    // COMBO) are retired, and the vouchers.value_type column is unmapped
    // legacy history. That is also what makes the per-voucher fee arithmetic
    // sound — a fee percentage now always multiplies money, never a
    // percentage masquerading as one.
    @Column(name = "face_value", precision = 19, scale = 4)
    private BigDecimal value;

    @Column(name = "currency", length = 8)
    private String currency;

    /**
     * The BASE-currency (USD) worth of {@link #value}, frozen at the rate in
     * force when the voucher was ISSUED (V38) — the liability the platform took
     * on by promising this discount. Read it back; never recompute it, or the
     * outstanding-voucher book would swing daily on currency movement alone,
     * with nothing issued and nothing redeemed.
     *
     * <p>Null means there is no USD liability figure, never zero, and for three
     * different reasons: the voucher isn't denominated in money at all (PERCENT
     * / FREE_ITEM / COMBO — "10% off" is not 10 of anything and must never be
     * run through an exchange rate), it predates V38, or it is a pre-V38 non-USD
     * voucher whose issue-time rate is unknown.
     */
    @Column(name = "base_value", precision = 19, scale = 4)
    private BigDecimal baseValue;

    /**
     * The {@link ExchangeRate} row whose rate produced {@link #baseValue}. Null
     * when no conversion was needed or recorded: a USD voucher (identity), a
     * non-money value type, or a pre-V38 row.
     */
    @Column(name = "fx_rate_id")
    private UUID fxRateId;

    @Column(name = "uses_remaining", nullable = false)
    private int usesRemaining = 1;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "viewed_at")
    private Instant viewedAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** When the pre-expiry warning was sent (or consumed) by the daily
     *  ExpiryWarningSweeper. Null = not yet warned; at most one warning. */
    @Column(name = "expiry_warned_at")
    private Instant expiryWarnedAt;

    @Column(name = "campaign_source", length = 200)
    private String campaignSource;

    // Single-hop p2p transfer (V34). A voucher may change hands exactly once:
    // issued -> transferred -> redeemed. `transferredAt == null` IS the
    // "may still be transferred" test — see VoucherService.transfer.
    //
    // The from-* pair records the assignee the voucher moved AWAY from. The
    // current holder remains assignedUserId / assigneePhone, which the transfer
    // overwrites, so without these two columns the previous owner would be
    // unrecoverable the moment the transfer commits.
    @Column(name = "transferred_at")
    private Instant transferredAt;

    @Column(name = "transferred_from_user_id")
    private UUID transferredFromUserId;

    @Column(name = "transferred_from_phone", length = 32)
    private String transferredFromPhone;

    @Version
    private long version;

    public enum Status { ISSUED, DELIVERED, VIEWED, REDEEMED, PARTIALLY_USED, EXPIRED, REVOKED }
    public enum DeliveryChannel { SMS, WHATSAPP, EMAIL, PUSH, POS, NONE }

    /**
     * The only two voucher shapes since V45. The old CAMPAIGN / REFERRAL /
     * CORPORATE values were distribution labels, not redemption semantics —
     * how a voucher behaves at the till was always {@code usesRemaining}.
     * SINGLE_USE fixes the usage limit at 1; MULTI_USE takes an explicit
     * limit of 2 or more at issue.
     */
    public enum VoucherType { SINGLE_USE, MULTI_USE }
}
