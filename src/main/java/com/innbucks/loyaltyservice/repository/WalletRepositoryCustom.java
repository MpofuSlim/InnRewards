package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Wallet;

import java.util.Optional;
import java.util.UUID;

/** Wallet operations a derived or {@code @Query} method cannot express. */
public interface WalletRepositoryCustom {

    /**
     * Locks the wallet row ({@code FOR NO KEY UPDATE}) and returns it with the
     * state it has UNDER the lock, whether or not this transaction had already
     * loaded it.
     *
     * <p>This replaces a {@code @Lock(PESSIMISTIC_WRITE)} query, which was wrong
     * for the common case. Nearly every caller reads the wallet before changing
     * it (a checkout reads the balance, then the MAIN wallet), and when a locking
     * query returns an entity the persistence context already holds, Hibernate
     * keeps the copy it read earlier and compares versions. If another
     * transaction had committed in between, the lock failed with
     * {@code ObjectOptimisticLockingFailureException} instead of waiting its turn:
     * two purchases by one customer at the same moment, and one of them failed.
     * A refresh with the lock re-reads the row instead.
     */
    Optional<Wallet> lockForUpdate(UUID walletId);
}
