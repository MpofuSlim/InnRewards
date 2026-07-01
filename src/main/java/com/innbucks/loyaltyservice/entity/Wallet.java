package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "wallets", indexes = {
        @Index(name = "idx_wallet_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Wallet {

    @Id
    @GeneratedValue
    private UUID id;

    // Global customer key. A wallet belongs to the CUSTOMER (identified by the
    // platform-stable phone number), not to a per-tenant LoyaltyUser projection.
    // One MAIN wallet per phone across the whole super-app, so points earned at
    // any tenant are spendable at any tenant ("one balance").
    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    // Legacy / informational only. Before the global-wallet change a wallet was
    // owned per (tenant, LoyaltyUser); these columns are retained nullable for
    // back-compat and audit (first owner) but are NOT the resolution key.
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String label = "Main";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type = Type.MAIN;

    /**
     * Optional pocket label, e.g. "FUEL", "PROMO". When set, only matching
     * rewards may be credited here and only matching redemptions may debit.
     */
    @Column(length = 40)
    private String pocket;

    @Version
    private long version;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "locked_until")
    private LocalDate lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Type { MAIN, SUB, SAVINGS }
}
