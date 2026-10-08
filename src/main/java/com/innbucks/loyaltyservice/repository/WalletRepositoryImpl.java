package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Wallet;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import java.util.Optional;
import java.util.UUID;

/** Spring Data fragment behind {@link WalletRepositoryCustom}, found by its {@code Impl} suffix. */
class WalletRepositoryImpl implements WalletRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<Wallet> lockForUpdate(UUID walletId) {
        // Write any pending changes first. The refresh below re-reads the row and
        // overwrites this transaction's copy, so an unflushed change to the wallet
        // would be lost, and a wallet persisted but not yet inserted would not be
        // found at all. (The locking query this replaces got the same effect from
        // Hibernate's auto-flush whenever the wallet had pending changes.)
        entityManager.flush();
        Wallet wallet = entityManager.find(Wallet.class, walletId);
        if (wallet == null) {
            return Optional.empty();
        }
        entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(wallet);
    }
}
