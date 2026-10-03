package com.innbucks.loyaltyservice.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a side effect only once the surrounding transaction has COMMITTED.
 *
 * <p>Every customer message this service sends (voucher delivery, the sender
 * copy, points earned / redeemed / transferred / adjusted, the tenant-attach
 * ping) describes work done inside a transaction. Handing it to the
 * {@code @Async} executor from INSIDE that transaction has two faults: a
 * transaction that then rolls back has already told the customer something
 * that did not happen, and the hand-off itself runs while the caller still
 * holds its wallet / voucher / order row locks. Routing the hand-off through
 * here makes it the last thing that happens, after the commit, with the locks
 * already released — and never at all on a rollback.
 *
 * <ul>
 *   <li><b>Inside a transaction</b> (synchronization active): registered as an
 *       {@code afterCommit} callback. A rollback discards it.</li>
 *   <li><b>Outside one</b> (a unit test, a non-transactional caller): runs
 *       now, which is exactly what the caller did before.</li>
 * </ul>
 *
 * <p><b>Never throws.</b> An exception escaping an {@code afterCommit}
 * callback reaches the code that committed — a caller would then answer an
 * error for work that is already durable, and invite a retry of it. A failed
 * hand-off is a WARN (no message content, no phone numbers) and the work stands.
 * The task itself is expected to be the cheap {@code @Async} submission; the
 * send happens on the executor.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    public static void run(Runnable task) {
        if (task == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runSafely(task);
                }
            });
            return;
        }
        runSafely(task);
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.warn("After-commit notification hand-off failed; the committed work stands: {}",
                    e.getClass().getSimpleName());
        }
    }
}
