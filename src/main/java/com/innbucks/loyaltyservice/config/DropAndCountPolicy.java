package com.innbucks.loyaltyservice.config;

import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the notification executor does when it is saturated: <b>drop the
 * message and count it</b> (owner decision). It never throws and never runs
 * the task on the caller.
 *
 * <ul>
 *   <li><b>Not {@code CallerRunsPolicy}</b> (the previous setting). The caller
 *       is an HTTP thread inside a spend, or the scheduler — running an SMS
 *       (3s connect / 30s read) or WhatsApp send inline there is exactly the
 *       stall the executor exists to prevent, and before notifications moved
 *       after commit it happened while the caller held wallet / voucher / order
 *       row locks.</li>
 *   <li><b>Not {@code AbortPolicy}.</b> Spring turns the rejection into a
 *       {@code TaskRejectedException}; thrown from a caller still inside its
 *       transaction it rolls back a spend over a courtesy message, and thrown
 *       from an after-commit callback it reports an error for work already
 *       committed.</li>
 *   <li><b>Not a silent {@code DiscardPolicy}.</b> A dropped customer message
 *       must be visible: {@code loyalty.notify.rejected{executor}} counts every
 *       one, registered at 0 at startup so {@code increase()} sees the first
 *       drop, and a WARN says it happened.</li>
 * </ul>
 *
 * <p>The WARN carries no message content and no phone number — the task is an
 * opaque {@code Runnable} and is never printed — and is throttled to one line
 * per {@link #LOG_INTERVAL_NANOS} carrying the number dropped since the last
 * line, so a saturated pool cannot flood the log. The counter is exact.
 */
public final class DropAndCountPolicy implements RejectedExecutionHandler {

    private static final Logger log = LoggerFactory.getLogger(DropAndCountPolicy.class);
    static final long LOG_INTERVAL_NANOS = 10_000_000_000L; // 10s

    private final String executorName;
    private final Counter rejected;
    private final AtomicLong sinceLastLog = new AtomicLong();
    private final AtomicLong lastLogNanos = new AtomicLong(System.nanoTime() - LOG_INTERVAL_NANOS);

    /** @param rejected may be null (no registry in a bare unit context) — then only the WARN. */
    public DropAndCountPolicy(String executorName, Counter rejected) {
        this.executorName = executorName;
        this.rejected = rejected;
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        try {
            if (rejected != null) {
                rejected.increment();
            }
            sinceLastLog.incrementAndGet();
            long now = System.nanoTime();
            long last = lastLogNanos.get();
            if (now - last >= LOG_INTERVAL_NANOS && lastLogNanos.compareAndSet(last, now)) {
                long n = sinceLastLog.getAndSet(0);
                log.warn("Notification executor '{}' saturated: dropped {} message(s) since the last "
                                + "report (active={}, pool={}, queued={}); see loyalty.notify.rejected",
                        executorName, n,
                        executor == null ? -1 : executor.getActiveCount(),
                        executor == null ? -1 : executor.getPoolSize(),
                        executor == null ? -1 : executor.getQueue().size());
            }
        } catch (RuntimeException e) {
            // Never let the rejection path itself escape into the caller.
        }
    }
}
