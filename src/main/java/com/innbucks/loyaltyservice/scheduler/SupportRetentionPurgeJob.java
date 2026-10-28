package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Deletes customer-support log rows past their retention (V63): the
 * {@code support_activity} and {@code support_message} rows more than
 * {@code loyalty.retention.support-months} old (12 by default, never fewer).
 * {@code support_note} is never swept: notes are case history, not a log.
 *
 * <h2>The floor is the database's</h2>
 * V63's triggers refuse to delete a row younger than 12 months from either
 * table, whoever asks. The cutoff here is computed by Postgres too
 * ({@code now() - make_interval(months => ?)}), with the same clock the
 * trigger reads, so a run can never select a row the trigger then refuses.
 * A setting below 12 is raised to 12 with a WARN rather than letting every
 * batch fail on the trigger.
 *
 * <h2>Shape</h2>
 * The repositories stay append-only by shape (save and reads only); the delete
 * lives here, as plain SQL, and nowhere else. Otherwise the same as
 * {@link TokenRetentionPurgeJob}: one ShedLock-guarded run a night, batches in
 * their own short transactions, a cap per table per run, idempotent if two
 * replicas race, and a failure on one table does not stop the other.
 */
@Component
public class SupportRetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(SupportRetentionPurgeJob.class);

    /** V63's trigger floor. */
    static final int MIN_MONTHS = 12;

    private static final String DELETE_ACTIVITY = """
            DELETE FROM support_activity
             WHERE id IN (SELECT id FROM support_activity
                           WHERE created_at < now() - make_interval(months => ?)
                           ORDER BY created_at
                           LIMIT ?)
            """;

    private static final String DELETE_MESSAGES = """
            DELETE FROM support_message
             WHERE id IN (SELECT id FROM support_message
                           WHERE created_at < now() - make_interval(months => ?)
                           ORDER BY created_at
                           LIMIT ?)
            """;

    /** One table's share of a run. {@code complete} = drained, not stopped by the cap or an error. */
    record TableOutcome(long rows, int batches, boolean complete) {}

    record Outcome(TableOutcome activity, TableOutcome messages) {}

    private final JdbcTemplate jdbc;
    private final LoyaltyMetrics metrics;
    private final TransactionOperations tx;
    private final boolean enabled;
    private final int months;
    private final int batchSize;
    private final int maxBatches;

    @Autowired
    public SupportRetentionPurgeJob(DataSource dataSource,
                                    LoyaltyMetrics metrics,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${loyalty.retention.support-enabled:true}") boolean enabled,
                                    @Value("${loyalty.retention.support-months:12}") int months,
                                    @Value("${loyalty.retention.batch-size:1000}") int batchSize,
                                    @Value("${loyalty.retention.max-batches-per-run:500}") int maxBatches) {
        this(new JdbcTemplate(dataSource), metrics, new TransactionTemplate(transactionManager),
                enabled, months, batchSize, maxBatches);
    }

    SupportRetentionPurgeJob(JdbcTemplate jdbc, LoyaltyMetrics metrics, TransactionOperations tx,
                             boolean enabled, int months, int batchSize, int maxBatches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.tx = tx;
        this.enabled = enabled;
        if (months < MIN_MONTHS) {
            log.warn("loyalty.retention.support-months={} is below the {}-month floor the database enforces; "
                    + "using {}", months, MIN_MONTHS, MIN_MONTHS);
        }
        this.months = Math.max(months, MIN_MONTHS);
        this.batchSize = batchSize > 0 ? batchSize : TokenRetentionPurgeJob.DEFAULT_BATCH_SIZE;
        this.maxBatches = maxBatches > 0 ? maxBatches : TokenRetentionPurgeJob.DEFAULT_MAX_BATCHES;
    }

    @Scheduled(cron = "${loyalty.retention.support-purge-cron:0 40 3 * * *}")
    @SchedulerLock(name = "supportRetentionPurge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        if (!enabled) {
            return;
        }
        run();
    }

    /** One run; the test seam behind {@link #purge()}. */
    Outcome run() {
        TableOutcome activity = drain(LoyaltyMetrics.TABLE_SUPPORT_ACTIVITY, DELETE_ACTIVITY);
        TableOutcome messages = drain(LoyaltyMetrics.TABLE_SUPPORT_MESSAGE, DELETE_MESSAGES);
        log.info("Support retention purge (older than {} months): support_activity deleted={} ({} batches{}), "
                        + "support_message deleted={} ({} batches{})",
                months, activity.rows(), activity.batches(), activity.complete() ? "" : ", NOT drained",
                messages.rows(), messages.batches(), messages.complete() ? "" : ", NOT drained");
        return new Outcome(activity, messages);
    }

    private TableOutcome drain(String table, String sql) {
        long total = 0;
        int batches = 0;
        try {
            while (batches < maxBatches) {
                Integer deleted = tx.execute(status -> jdbc.update(sql, months, batchSize));
                int rows = deleted == null ? 0 : deleted;
                batches++;
                total += rows;
                metrics.incRetentionPurged(table, rows);
                if (rows < batchSize) {
                    return new TableOutcome(total, batches, true);
                }
            }
            log.warn("Support retention purge: {} hit the {}-batch cap ({} rows); the rest drains on the next run",
                    table, maxBatches, total);
        } catch (RuntimeException e) {
            log.error("Support retention purge: {} failed after {} batches ({} rows deleted)",
                    table, batches, total, e);
        }
        return new TableOutcome(total, batches, false);
    }
}
