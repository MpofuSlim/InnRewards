package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "voucher_redemptions", indexes = {
        @Index(name = "idx_redemption_voucher", columnList = "voucher_id"),
        @Index(name = "idx_redemption_user", columnList = "user_id"),
        @Index(name = "idx_redemption_merchant", columnList = "merchant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class VoucherRedemption {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "voucher_id", nullable = false)
    private UUID voucherId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "outlet_code", length = 80)
    private String outletCode;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result result = Result.SUCCESS;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "device_fingerprint", length = 128)
    private String deviceFingerprint;

    @Column(length = 200)
    private String reason;

    public enum Result { SUCCESS, REJECTED }
}
