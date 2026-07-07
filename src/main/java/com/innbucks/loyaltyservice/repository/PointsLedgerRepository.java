package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.PointsLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PointsLedgerRepository extends JpaRepository<PointsLedger, UUID> {
    List<PointsLedger> findTop50ByWalletIdOrderByCreatedAtDesc(UUID walletId);

    /**
     * Sum of every ledger delta for a wallet — the wallet's balance from the
     * append-only audit log, which is the financial source of truth. Used by
     * reconciliation to detect (and, when enabled, repair) drift between the
     * cached {@code wallets.balance} and the ledger. {@code COALESCE} returns 0
     * for a wallet with no entries rather than {@code null}.
     */
    @Query("SELECT COALESCE(SUM(p.delta), 0) FROM PointsLedger p WHERE p.walletId = :walletId")
    BigDecimal sumDeltaByWalletId(@Param("walletId") UUID walletId);

    /**
     * A04: the exact net amount a given transaction applied to the wallet
     * (0 when it never touched one — e.g. a non-positive earn that {@code post()}
     * did not credit). Reversals use this to compensate ONLY what actually
     * moved, instead of blindly negating the stored {@code pointsDelta} (which
     * would mint points from nothing). Backed by {@code idx_ledger_txn}.
     */
    @Query("SELECT COALESCE(SUM(p.delta), 0) FROM PointsLedger p WHERE p.transactionId = :transactionId")
    BigDecimal sumDeltaByTransactionId(@Param("transactionId") UUID transactionId);
}
