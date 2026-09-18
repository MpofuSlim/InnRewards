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

    // Sender identity (V46) — who the voucher is FROM, as it should read to the
    // recipient ("Tawanda Mpofu sent you a voucher"). Presentation facts, not
    // audit: the issuer_* columns below record who performed the API call (JWT,
    // never the body), while these may name a customer a staff member issued on
    // behalf of. Name is caller-supplied and HTML-stripped; phone defaults to
    // the issuing caller's own JWT phone. Both null on bulk/campaign stock and
    // pre-V46 rows.
    @Column(name = "sender_name", length = 200)
    private String senderName;

    @Column(name = "sender_phone", length = 32)
    private String senderPhone;

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

    /**
     * When dispatch to the holder's phone was ATTEMPTED — not when it arrived.
     * Stamped at issue for any voucher with a real delivery channel, before
     * the {@code @Async} WhatsApp/SMS send runs, and never revised.
     *
     * <p>This is the surviving half of the retired DELIVERED status (V48): the
     * timestamp says exactly what it means and claims nothing about receipt,
     * where a status value called DELIVERED read as a promise the service
     * never verified. A non-null value here alongside a failed send is normal
     * and is what the gateway's warning logs are for.
     */
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

    /**
     * The voucher lifecycle. {@code ISSUED} is the live, wholly-unused state
     * every voucher starts in and stays in until someone opens or spends it.
     *
     * <p><b>DELIVERED was merged into ISSUED in V48.</b> It was stamped at
     * save time, before the async send it named had run, and was never
     * corrected when that send failed — so it distinguished nothing (every
     * voucher issued to a named person skipped ISSUED entirely) while implying
     * something untrue (that the customer had received it). "Dispatch was
     * attempted at T" now lives where it belongs, on {@link #deliveredAt}.
     * Do not reintroduce a delivery state here: an outbound-message outcome is
     * not a stage of the voucher's life, and if delivery confirmation is ever
     * wanted it belongs in its own column fed by a gateway receipt.
     */
    public enum Status { ISSUED, VIEWED, REDEEMED, PARTIALLY_USED, EXPIRED, REVOKED }

    /**
     * The LIVE states — a voucher that still carries unredeemed value and so
     * still counts as an outstanding liability: it can be shown in a wallet,
     * transferred, redeemed, warned about before expiry, and summed into the
     * outstanding book.
     *
     * <p>This set was copy-pasted into six places (three lookups here, the
     * report's outstanding filter, the expiring-soon query, the public test
     * surface) plus two JPQL {@code IN} lists — which is precisely why
     * retiring ONE status value in V48 had to touch twelve files. It lives
     * here once now; the JPQL copies are unavoidable (a query string cannot
     * reference a constant) and are flagged in place to be changed with it.
     */
    public static final java.util.List<Status> LIVE_STATUSES =
            java.util.List.of(Status.ISSUED, Status.VIEWED, Status.PARTIALLY_USED);

    public enum DeliveryChannel { SMS, WHATSAPP, EMAIL, PUSH, POS, NONE }

    /**
     * SINGLE_USE is the only shape issued. The old CAMPAIGN / REFERRAL /
     * CORPORATE values were distribution labels, not redemption semantics (V45);
     * MULTI_USE was retired after that (owner decision, 2026-09-18) because it
     * never had money semantics that worked — {@code value} is a face amount and
     * {@code usesRemaining} a bare counter, with no remaining balance anywhere,
     * so a "$5, three uses" voucher handed the till $5 three times.
     * {@code VoucherService.resolveUsageLimit} refuses to issue one.
     *
     * <p><b>V49 collapsed the outstanding stock</b> (owner decision, the cell
     * being in test phase): every live MULTI_USE voucher keeps exactly one use,
     * every row was retyped SINGLE_USE, and {@code chk_vouchers_voucher_type}
     * now refuses the value outright. Refusing to MINT one was only half the
     * retirement — the half that does not hold the money, since a live
     * MULTI_USE row went on paying its full face value per use regardless of
     * what the issue endpoint would accept.
     *
     * <p><b>MULTI_USE stays on this enum even so, and must not be deleted.</b>
     * The reason is no longer live stock — there is none — but hydration
     * safety: {@code voucher_type} is {@code @Enumerated(EnumType.STRING)}, so
     * a row holding a string this enum lacks makes Hibernate throw PER ROW at
     * query execution, with no compile, boot or CI signal (every
     * {@code @SpringBootTest} applies Flyway first, so the suite stays green
     * and the breakage appears only against a cell with real history). A
     * restore from a pre-V49 backup, a replica that has not caught up, or any
     * row that predates the migration would take out every read path touching
     * it. The constant costs nothing and is the difference between a stale row
     * being merely odd and being a 500. Deleting it buys tidiness and risks an
     * outage — do not trade that way.
     */
    public enum VoucherType { SINGLE_USE, MULTI_USE }
}
