package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants", indexes = {
        @Index(name = "idx_merchant_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Merchant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    // Set only on auto-provisioned ticketing merchants: the event organizer
    // (user_uuid) this merchant represents. Unique when set (uk_merchant_organizer).
    @Column(name = "organizer_uuid")
    private UUID organizerUuid;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 80)
    private String category;

    @Column(length = 200)
    private String location;

    @Column(length = 8)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 20)
    private BillingCycle billingCycle = BillingCycle.MONTHLY;

    // Per-voucher fee config. Three modes per side (see FeeType): FIXED is a
    // flat amount, PERCENTAGE is computed off the voucher's face value, and
    // FIXED_PLUS_PERCENTAGE is the sum. Issued and redeemed are configured
    // independently so a merchant can charge differently for each leg.

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_issued_type", nullable = false, length = 30)
    private FeeType feeIssuedType = FeeType.FIXED;

    @Column(name = "fee_issued_fixed", precision = 19, scale = 6, nullable = false)
    private BigDecimal feeIssuedFixed = BigDecimal.ZERO;

    /** Whole-number percent, e.g. 2.5 means 2.5%. The /100 lives in {@link com.innbucks.loyaltyservice.service.MerchantFeeCalculator}. */
    @Column(name = "fee_issued_percentage", precision = 7, scale = 4, nullable = false)
    private BigDecimal feeIssuedPercentage = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_redeemed_type", nullable = false, length = 30)
    private FeeType feeRedeemedType = FeeType.FIXED;

    @Column(name = "fee_redeemed_fixed", precision = 19, scale = 6, nullable = false)
    private BigDecimal feeRedeemedFixed = BigDecimal.ZERO;

    /** Whole-number percent, e.g. 2.5 means 2.5%. */
    @Column(name = "fee_redeemed_percentage", precision = 7, scale = 4, nullable = false)
    private BigDecimal feeRedeemedPercentage = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    // Email of the user-service identity that admins this merchant. Stamped
    // at create time from the JWT subject so AuthService can resolve a
    // MERCHANT_ADMIN's merchantId at login without manual binding.
    @Column(name = "admin_email", length = 255)
    private String adminEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum BillingCycle { WEEKLY, MONTHLY, DAILY }
    public enum Status { ACTIVE, INACTIVE }

    /**
     * How a per-voucher fee is computed:
     * <ul>
     *   <li>{@code FIXED} — fee = fee*Fixed, ignoring the voucher's face value.</li>
     *   <li>{@code PERCENTAGE} — fee = voucherFaceValue × fee*Percentage / 100.</li>
     *   <li>{@code FIXED_PLUS_PERCENTAGE} — sum of both legs above.</li>
     * </ul>
     */
    public enum FeeType { FIXED, PERCENTAGE, FIXED_PLUS_PERCENTAGE }
}
