package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_attempts", indexes = {
        @Index(name = "idx_fraud_voucher_code", columnList = "voucher_code"),
        @Index(name = "idx_fraud_device", columnList = "device_fingerprint"),
        @Index(name = "idx_fraud_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class FraudAttempt {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "voucher_code", length = 64)
    private String voucherCode;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "device_fingerprint", length = 128)
    private String deviceFingerprint;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Reason reason;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Reason {
        INVALID_CODE,
        BAD_SIGNATURE,
        EXPIRED,
        ALREADY_REDEEMED,
        USAGE_EXCEEDED,
        WRONG_MERCHANT,
        BLOCKED_DEVICE,
        BLOCKED_USER,
        QR_REUSED,
        QR_EXPIRED,
        QR_BAD_SIGNATURE
    }
}
