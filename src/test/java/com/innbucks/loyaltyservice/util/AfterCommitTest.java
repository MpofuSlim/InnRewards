package com.innbucks.loyaltyservice.util;

import com.innbucks.loyaltyservice.testsupport.RecordingTransactionManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The after-commit hand-off every customer message goes through: nothing is
 * sent for a transaction that rolls back, nothing is sent before the commit,
 * and a failed hand-off never reaches the code that committed.
 */
class AfterCommitTest {

    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    private final TransactionTemplate tx = new TransactionTemplate(txManager);

    @Test
    void outsideATransaction_runsNow() {
        AtomicInteger ran = new AtomicInteger();
        AfterCommit.run(ran::incrementAndGet);
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void insideATransaction_runsOnlyAfterTheCommit() {
        List<String> events = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            AfterCommit.run(() -> events.add("sent after " + txManager.commits.get() + " commit(s)"));
            events.add("work done");
        });
        assertThat(events).containsExactly("work done", "sent after 1 commit(s)");
    }

    @Test
    void aRolledBackTransaction_sendsNothing() {
        AtomicInteger ran = new AtomicInteger();
        tx.executeWithoutResult(status -> {
            AfterCommit.run(ran::incrementAndGet);
            status.setRollbackOnly();
        });
        assertThat(txManager.rollbacks.get()).isEqualTo(1);
        assertThat(ran.get()).isZero();
    }

    @Test
    void aTransactionThatThrows_sendsNothing() {
        AtomicInteger ran = new AtomicInteger();
        assertThatCode(() -> tx.executeWithoutResult(status -> {
            AfterCommit.run(ran::incrementAndGet);
            throw new IllegalStateException("the spend failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ran.get()).isZero();
    }

    @Test
    void aFailedCommit_sendsNothing() {
        AtomicInteger ran = new AtomicInteger();
        txManager.failNextCommit();
        assertThatCode(() -> tx.executeWithoutResult(status -> AfterCommit.run(ran::incrementAndGet)))
                .isInstanceOf(RuntimeException.class);
        assertThat(ran.get()).isZero();
    }

    @Test
    void aHandOffThatThrows_neverReachesTheCodeThatCommitted() {
        AtomicInteger later = new AtomicInteger();
        assertThatCode(() -> tx.executeWithoutResult(status -> {
            AfterCommit.run(() -> {
                throw new IllegalStateException("executor refused");
            });
            AfterCommit.run(later::incrementAndGet);
        })).doesNotThrowAnyException();
        assertThat(txManager.commits.get()).isEqualTo(1);
        // One failed hand-off does not cost the next one.
        assertThat(later.get()).isEqualTo(1);

        assertThatCode(() -> AfterCommit.run(() -> {
            throw new IllegalStateException("no transaction either");
        })).doesNotThrowAnyException();
        assertThatCode(() -> AfterCommit.run(null)).doesNotThrowAnyException();
    }
}
