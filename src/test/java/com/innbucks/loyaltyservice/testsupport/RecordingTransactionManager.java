package com.innbucks.loyaltyservice.testsupport;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A database-free transaction manager for unit tests that need REAL Spring
 * transaction semantics — synchronization, {@code afterCommit} callbacks,
 * {@code isActualTransactionActive()} — without a datasource. Counts commits
 * and rollbacks, and can be told to fail the next commit.
 */
public class RecordingTransactionManager extends AbstractPlatformTransactionManager {

    public final AtomicInteger begins = new AtomicInteger();
    public final AtomicInteger commits = new AtomicInteger();
    public final AtomicInteger rollbacks = new AtomicInteger();
    private volatile boolean failNextCommit;

    public void failNextCommit() {
        this.failNextCommit = true;
    }

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        begins.incrementAndGet();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        if (failNextCommit) {
            failNextCommit = false;
            throw new TransactionSystemException("simulated commit failure");
        }
        commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        rollbacks.incrementAndGet();
    }
}
