package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A merchant a partner may act for (V62), named in every request by
 * {@link #merchantCode} (e.g. {@code MWOS}). The per-merchant settings live here
 * because merchants differ: a betting shop and a restaurant need different
 * limits and take different payment methods. A null setting means the cell
 * default ({@code loyalty.voucher-partner.*}).
 */
@Entity
@Table(name = "voucher_partner_merchants")
@Getter
@Setter
@NoArgsConstructor
public class VoucherPartnerMerchant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** What the partner sends as {@code merchant}; unique per partner. */
    @Column(name = "merchant_code", nullable = false, length = 32)
    private String merchantCode;

    /** A POS generate without a registered terminal is refused when true. */
    @Column(name = "require_terminal", nullable = false)
    private boolean requireTerminal;

    /** A redeem must carry the verified phone of the customer's account on the
     *  partner's platform, equal to the voucher's holder phone. */
    @Column(name = "bind_redeem_to_holder", nullable = false)
    private boolean bindRedeemToHolder = true;

    /** Comma-separated funding methods this merchant accepts; null = every
     *  method the cell supports. */
    @Column(name = "funding_methods", length = 200)
    private String fundingMethods;

    @Column(name = "max_value", precision = 19, scale = 4)
    private BigDecimal maxValue;

    @Column(name = "per_phone_daily_count")
    private Integer perPhoneDailyCount;

    @Column(name = "per_phone_daily_value", precision = 19, scale = 4)
    private BigDecimal perPhoneDailyValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VoucherPartner.Status status = VoucherPartner.Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
