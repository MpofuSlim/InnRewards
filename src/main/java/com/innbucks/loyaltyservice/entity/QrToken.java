package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "qr_tokens", uniqueConstraints = {
        @UniqueConstraint(name = "uk_qr_token", columnNames = "token")
}, indexes = {
        @Index(name = "idx_qr_tenant", columnList = "tenant_id"),
        @Index(name = "idx_qr_expires", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class QrToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private TransactionType transactionType;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 8)
    private String currency = "USD";

    @Column(nullable = false, length = 64, unique = true)
    private String token;

    @Column(nullable = false, length = 128)
    private String signature;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** V58: the ISSUING caller's token shopId (null when the issuer has none —
     *  a merchant admin, a customer's transfer QR, or a pre-V58 row). The earn a
     *  merchant QR produces is attributed to this outlet, never to the scanner's. */
    @Column(name = "shop_id")
    private UUID shopId;

    /** V58: the ledger row consume produced — the earn for a MERCHANT QR; null
     *  for a USER (transfer) QR, an unconsumed one, or a pre-V58 consume. */
    @Column(name = "transaction_id")
    private UUID transactionId;

    /** V58: points credited by the consume (earn delta, or points a transfer QR
     *  moved). Null until consumed, and on pre-V58 consumes — never read as zero. */
    @Column(name = "points_awarded", precision = 19, scale = 4)
    private BigDecimal pointsAwarded;

    public enum SourceType { MERCHANT, USER }
}
