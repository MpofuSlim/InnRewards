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
        @Index(name = "idx_txn_tenant_shop_created", columnList = "tenant_id,shop_id,created_at")
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Status { POSTED, REVERSED }
}
