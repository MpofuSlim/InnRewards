package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loyalty_transactions", indexes = {
        @Index(name = "idx_txn_tenant_merchant", columnList = "tenant_id,merchant_id"),
        @Index(name = "idx_txn_user", columnList = "user_id"),
        @Index(name = "idx_txn_reference", columnList = "reference"),
        @Index(name = "idx_txn_created_at", columnList = "created_at"),
        @Index(name = "idx_txn_tenant_shop_created", columnList = "tenant_id,shop_id,created_at"),
        @Index(name = "idx_txn_posted_by", columnList = "posted_by")
})
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyTransaction {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    // Shop (outlet) that produced this transaction. Nullable because some
    // sources (manual ADJUSTMENT, P2P transfer, rule-engine accruals not
    // tied to a checkout) have no shop attribution. Populated whenever
    // the caller's JWT carries a shopId claim — i.e. SHOP_USER cashier
    // ringing up a customer.
    @Column(name = "shop_id")
    private UUID shopId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionType type;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 8)
    private String currency = "USD";

    @Column(name = "points_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal pointsDelta = BigDecimal.ZERO;

    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "reference", length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.POSTED;

    @Column(name = "reverses_id")
    private UUID reversesId;

    // Earn-integrity attribution (V32). WHO created this row: the caller's
    // user-service UUID from the JWT (CallerDetails.currentUserId()). NULL for
    // server-to-server flows and for rows predating V32 — "unattributed
    // legacy", never an error. Every fraud control (concentration reporting,
    // pair detection, discipline) keys off this column; it cannot be
    // backfilled, which is why it ships ahead of the controls it enables.
    @Column(name = "posted_by")
    private UUID postedBy;

    // How an EARN arrived (TYPED_PHONE / QR_PRESENCE / CHECKOUT_S2S). NULL for
    // non-earn rows and legacy rows. The SELF_EARN and REFERENCE_REQUIRED
    // guards fire only on TYPED_PHONE — see EarnChannel's javadoc.
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20)
    private EarnChannel channel;

    /**
     * The invoice whose billing period covered this transaction (IN-9, V33).
     * Stamped by {@code InvoicingService.generate} when the period is invoiced,
     * so a points report can name the invoice a row was billed on.
     *
     * <p>A back-reference, not a funding link: points are what an invoice is
     * computed FROM, never the other way round.
     *
     * <p>Null means "not billed on any invoice" — either the period hasn't been
     * invoiced yet, or the merchant had no billable voucher activity that
     * period and {@code InvoicingService} skipped the zero-total invoice
     * entirely. Points can legitimately exist with no invoice, so null is a
     * real answer rather than missing data.
     */
    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Status { POSTED, REVERSED }
}
