package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A batch ("lot") of points earned in one credit, carrying its own expiry.
 * Points expire per-lot: a lot expires {@code expires_at} after it was earned,
 * and redemptions burn lots FIFO (soonest-to-expire first). The wallet's cached
 * balance equals the sum of {@code remaining_amount} across the customer's
 * still-live lots; {@link com.innbucks.loyaltyservice.service.WalletService}
 * maintains that invariant and the daily
 * {@link com.innbucks.loyaltyservice.scheduler.PointExpirySweeper} releases
 * expired remainders (breakage) to the ledger.
 */
@Entity
@Table(name = "point_lot", indexes = {
        @Index(name = "idx_point_lot_wallet_active", columnList = "wallet_id,expires_at"),
        @Index(name = "idx_point_lot_expiry", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class PointLot {

    @Id
    @GeneratedValue
    private UUID id;

    /** The customer's global wallet this lot belongs to. */
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /** Attribution: the tenant where these points were earned. Null for
     *  backfilled lots and platform-level credits (no single tenant). */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** The earning LoyaltyTransaction, when there is one. */
    @Column(name = "source_transaction_id")
    private UUID sourceTransactionId;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal originalAmount;

    /** Points still live in this lot — decremented by FIFO burns and zeroed on expiry. */
    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal remainingAmount;

    @Column(name = "earned_at", nullable = false)
    private Instant earnedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
