package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One fee decision by InnBucks (V67): a tenant's default voucher fees
 * ({@link #merchantId} null) or one merchant's own. Written only through
 * {@code /loyalty/fees/**} by a holder of {@code loyalty-fees:manage} or
 * SUPER_ADMIN; never by a merchant.
 *
 * <p>Append-only by shape ({@link Immutable}, a repository with no update or
 * delete) and by trigger: a change is a new row, so the rows ARE the history. A
 * {@link #cleared} row withdraws its scope's schedule, and resolution falls
 * through to the next level ({@code EffectiveFees}).
 */
@Entity
@Immutable
@Table(name = "fee_schedules")
@Getter
@Setter
@NoArgsConstructor
public class FeeSchedule {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Null = the tenant's default; set = this merchant's own fees. */
    @Column(name = "merchant_id")
    private UUID merchantId;

    /** TRUE = this scope's schedule is withdrawn; every fee column is null. */
    @Column(nullable = false)
    private boolean cleared;

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

    /** Why (e.g. "Signed rate card, Nov 2026"). Shown in the history. */
    @Column(length = 500)
    private String note;

    /** Who set it: the caller's login (email), else their user id. */
    @Column(name = "set_by", nullable = false, length = 254)
    private String setBy;

    @Column(name = "set_by_user_id")
    private UUID setByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public boolean isTenantDefault() {
        return merchantId == null;
    }
}
