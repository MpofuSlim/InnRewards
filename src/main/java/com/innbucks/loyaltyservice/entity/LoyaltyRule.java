package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loyalty_rules", indexes = {
        @Index(name = "idx_rule_tenant_merchant", columnList = "tenant_id,merchant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyRule extends Auditable {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Null = global template applicable to all merchants under the tenant.
     * Non-null = merchant-specific override.
     */
    @Column(name = "merchant_id")
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private TransactionType transactionType;

    @Column(name = "points_per_unit", nullable = false, precision = 19, scale = 6)
    private BigDecimal pointsPerUnit = BigDecimal.ONE;

    @Column(precision = 19, scale = 4)
    private BigDecimal multiplier = BigDecimal.ONE;

    @Column(name = "max_points_per_txn", precision = 19, scale = 4)
    private BigDecimal maxPointsPerTxn;

    @Column(length = 40)
    private String pocket;

    /**
     * Earning floor (V29): a transaction amount strictly below this earns ZERO
     * points. Null = no floor at this level; a merchant-specific rule with a
     * null floor inherits the global rule's floor (RulesEngine).
     */
    @Column(name = "min_transaction_amount", precision = 19, scale = 2)
    private BigDecimal minTransactionAmount;

    // Voucher fee schedules at rule level (V29) — the tenant STANDARD when set
    // on a global rule, a per-merchant override when set on a merchant rule.
    // All nullable: null type = "not configured at this level, inherit"
    // (resolution order lives in EffectiveFees). Same shapes/semantics as the
    // merchant-record columns (percentage is whole-number percent, 2.5 = 2.5%).
    @Enumerated(EnumType.STRING)
    @Column(name = "fee_issued_type", length = 30)
    private Merchant.FeeType feeIssuedType;

    @Column(name = "fee_issued_fixed", precision = 19, scale = 6)
    private BigDecimal feeIssuedFixed;

    @Column(name = "fee_issued_percentage", precision = 7, scale = 4)
    private BigDecimal feeIssuedPercentage;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_redeemed_type", length = 30)
    private Merchant.FeeType feeRedeemedType;

    @Column(name = "fee_redeemed_fixed", precision = 19, scale = 6)
    private BigDecimal feeRedeemedFixed;

    @Column(name = "fee_redeemed_percentage", precision = 7, scale = 4)
    private BigDecimal feeRedeemedPercentage;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;
}
