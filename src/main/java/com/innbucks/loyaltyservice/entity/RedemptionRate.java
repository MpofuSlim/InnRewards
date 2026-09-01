package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One effective-dated entry in the platform redemption formula (business-model
 * point 4): how many points redeem one unit of currency.
 *
 * <p><b>Platform-wide by construction.</b> There is deliberately no
 * {@code tenantId} or {@code merchantId} here. A merchant sets how points are
 * EARNED ({@link LoyaltyRule#getPointsPerUnit()}); the InnBucks platform — which
 * carries the liability for every outstanding point — sets what a point is WORTH
 * when spent. Giving a merchant nowhere to write this is the schema-level
 * guarantee that redemption value can never be a merchant decision.
 *
 * <p><b>Append-only + effective-dated.</b> A rate is never mutated or deleted; a
 * new row supersedes it. The rate in force at instant {@code T} is the row with
 * the greatest {@code effectiveFrom <= T} for the currency (ties broken by
 * {@code createdAt}). See
 * {@link com.innbucks.loyaltyservice.service.RedemptionRateService}.
 */
@Entity
@Table(name = "redemption_rates", indexes = {
        @Index(name = "idx_redemption_rate_lookup", columnList = "currency,effective_from,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class RedemptionRate {

    @Id
    private UUID id;

    /**
     * Points required to redeem ONE unit of currency. {@code 100} means 100
     * points buys $1 of value, so a $2.50 discount costs 250 points. Always
     * strictly positive — enforced at the service layer and by a CHECK
     * constraint (V35).
     */
    @Column(name = "points_per_unit", nullable = false, precision = 19, scale = 4)
    private BigDecimal pointsPerUnit;

    /** ISO 4217. Resolution is scoped by currency so USD and other rails never cross. */
    @Column(nullable = false, length = 8)
    private String currency = "USD";

    /**
     * When this rate starts being in force — distinct from {@link #createdAt} so
     * an operator can schedule a change ahead of time; the resolver ignores a
     * future-dated row until its instant arrives.
     */
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    /** JWT userId of the operator who set it; null for the seeded bootstrap row. */
    @Column(name = "created_by")
    private UUID createdBy;

    /** Optional operator note — the WHY behind a rate change, shown in history. */
    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
