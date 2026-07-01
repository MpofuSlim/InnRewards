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

    public enum SourceType { MERCHANT, USER }
}
