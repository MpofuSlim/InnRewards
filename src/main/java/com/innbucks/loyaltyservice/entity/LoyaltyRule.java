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

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;
}
