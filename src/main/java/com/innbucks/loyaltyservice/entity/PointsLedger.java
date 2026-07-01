package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "points_ledger", indexes = {
        @Index(name = "idx_ledger_wallet", columnList = "wallet_id"),
        @Index(name = "idx_ledger_txn", columnList = "transaction_id")
})
@Getter
@Setter
@NoArgsConstructor
public class PointsLedger {

    @Id
    @GeneratedValue
    private UUID id;

    // Attribution: the tenant where the balance change originated. Nullable
    // because expiry/breakage entries belong to no single tenant (the wallet is
    // global). Normal earn/redeem/transfer entries still carry it.
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal delta;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
