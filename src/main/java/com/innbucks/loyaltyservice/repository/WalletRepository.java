package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID>, WalletRepositoryCustom {

    /** The customer's single wallet of a given type (one MAIN per phone). */
    Optional<Wallet> findFirstByPhoneNumberAndType(String phoneNumber, Wallet.Type type);

    /** Every wallet (MAIN + pockets) for a customer. */
    List<Wallet> findByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * Serialises the transactions that CREATE a customer's records for one
     * phone: their {@code loyalty_users} projection and their global MAIN wallet.
     * A transaction-scoped Postgres advisory lock, released at commit or rollback.
     *
     * <p>Two checkouts for a phone loyalty has never seen both used to find
     * nothing and both insert; the loser hit {@code uk_user_tenant_phone} (or
     * {@code uk_wallet_phone_type_pocket}) and the whole checkout failed, because
     * a unique violation aborts the Postgres transaction. Catching it cannot help
     * for the same reason: nothing more, not even a re-read, runs in an aborted
     * transaction. So the creator takes this lock, looks again, and creates only
     * if the row is still missing. The second creator waits here until the first
     * commits, then finds its rows.
     *
     * <p>Only taken on a MISS, so a returning customer never pays for it. Keyed by
     * the phone alone (not tenant + phone) because the wallet is global: two
     * tenants' first purchases for one phone race on the same wallet. A hash
     * collision between two phones only makes one wait for the other briefly.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('loyalty-phone-create:' || :phone, 0))",
            nativeQuery = true)
    Integer lockPhoneForCreate(@Param("phone") String phone);

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
