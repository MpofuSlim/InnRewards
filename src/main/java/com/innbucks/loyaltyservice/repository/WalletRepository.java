package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /** The customer's single wallet of a given type (one MAIN per phone). */
    Optional<Wallet> findFirstByPhoneNumberAndType(String phoneNumber, Wallet.Type type);

    /** Every wallet (MAIN + pockets) for a customer. */
    List<Wallet> findByPhoneNumber(String phoneNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> lockById(@Param("id") UUID id);

    // ---- Reconciliation ----

    /**
     * A single wallet whose cached {@code balance} disagrees with the sum of its
     * ledger deltas. The ledger is the append-only source of truth; any non-zero
     * difference is drift that the reconciliation job alerts on (and optionally
     * repairs).
     */
    interface BalanceDrift {
        UUID getWalletId();
        BigDecimal getBalance();
        BigDecimal getLedgerSum();
    }

    /**
     * Every wallet for which {@code balance <> sum(points_ledger.delta)}, found
     * in a single grouped scan. The invariant is that they are always equal —
     * every balance mutation in {@code WalletService} writes a paired ledger
     * entry in the same transaction — so a non-empty result is a real
     * consistency defect to investigate. Wallets with no ledger entries
     * (freshly-created MAIN, explicit pockets) coalesce to a 0 sum and so are
     * only returned if their cached balance is itself non-zero.
     */
    @Query("""
            SELECT w.id AS walletId, w.balance AS balance, COALESCE(SUM(p.delta), 0) AS ledgerSum
            FROM Wallet w
            LEFT JOIN PointsLedger p ON p.walletId = w.id
            GROUP BY w.id, w.balance
            HAVING w.balance <> COALESCE(SUM(p.delta), 0)
            """)
    List<BalanceDrift> findBalanceDrift();
}
