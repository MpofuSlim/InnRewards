package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.repository.LoyaltyRefreshTokenRepository;
import com.innbucks.loyaltyservice.repository.QrTokenRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.ToIntFunction;

/**
 * Deletes what {@code qr_tokens} and {@code loyalty_refresh_tokens} no longer
 * need (V60). Neither table was ever pruned, so both grew by a row per QR
 * issued and per session refresh, forever.
 *
 * <h2>What goes</h2>
 * <ul>
 *   <li><b>QR tokens</b> that expired, and if consumed were consumed, more than
 *       {@code loyalty.retention.qr-tokens-days} ago. A QR lives minutes
 *       ({@code loyalty.qr.ttl-seconds}); nothing references the row (no foreign
 *       key, and its {@code transaction_id} points OUT to the earn row, which
 *       stays). Every reader is keyed by the token value and answers an absent
 *       row as an unknown token — status and consume both say 404
 *       {@code NOT_FOUND} "This QR code is invalid or has expired."</li>
 *   <li><b>Refresh-token chains</b> in which EVERY row ended — at
 *       {@code revoked_at} if revoked, else at {@code expires_at} — more than
 *       {@code loyalty.retention.refresh-tokens-days} ago. Whole chains only.</li>
 * </ul>
 *
 * <h2>What never goes</h2>
 * Any row of a chain that is, or within the retention period was, renewable.
 * A SPENT row is the reuse-detection tripwire: presenting it revokes the whole
 * chain, and the refresh path checks {@code used_at} before expiry, so it
 * matters even after its own window closed. Deleting it while the chain lives
 * would turn a replayed token into an ordinary "unknown" refusal and leave the
 * thief's copy of the chain's tip working. See
 * {@link LoyaltyRefreshTokenRepository#purgeChainsEndedBefore}.
 *
 * <h2>Shape</h2>
 * One ShedLock-guarded run per night. Each table drains in batches of
 * {@code batch-size}, each batch its own short transaction (no long lock on
 * either table, no connection held between batches); a short batch means
 * nothing else qualifies. A run stops after {@code max-batches-per-run} per
 * table and the rest drains on later runs, so a first purge of a large backlog
 * never outlives the lock. Deleting is idempotent: two replicas racing (a lock
 * that lapsed) delete disjoint or already-deleted rows and nothing else.
 * A failure on one table is logged and does not stop the other.
 */
@Component
public class TokenRetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(TokenRetentionPurgeJob.class);

    static final int DEFAULT_RETENTION_DAYS = 90;
    static final int DEFAULT_BATCH_SIZE = 1000;
    static final int DEFAULT_MAX_BATCHES = 500;

    /** One table's share of a run. {@code complete} = drained, not stopped by the cap or an error. */
    record TableOutcome(long rows, int batches, boolean complete) {}

    /** What a run did, for the log line and for tests. */
    record Outcome(TableOutcome qrTokens, TableOutcome refreshTokens) {}

    private final QrTokenRepository qrTokens;
    private final LoyaltyRefreshTokenRepository refreshTokens;
    private final LoyaltyMetrics metrics;
    private final TransactionOperations tx;
    private final boolean enabled;
    private final int qrRetentionDays;
    private final int refreshRetentionDays;
    private final int batchSize;
    private final int maxBatches;

    @Autowired
    public TokenRetentionPurgeJob(QrTokenRepository qrTokens,
                                  LoyaltyRefreshTokenRepository refreshTokens,
                                  LoyaltyMetrics metrics,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${loyalty.retention.enabled:true}") boolean enabled,
                                  @Value("${loyalty.retention.qr-tokens-days:90}") int qrRetentionDays,
                                  @Value("${loyalty.retention.refresh-tokens-days:90}") int refreshRetentionDays,
                                  @Value("${loyalty.retention.batch-size:1000}") int batchSize,
                                  @Value("${loyalty.retention.max-batches-per-run:500}") int maxBatches) {
        this(qrTokens, refreshTokens, metrics, new TransactionTemplate(transactionManager),
                enabled, qrRetentionDays, refreshRetentionDays, batchSize, maxBatches);
    }

    /**
     * Test seam: a transaction runner the test controls. A non-positive number
     * means its default — retention cannot be configured down to zero, which
     * for QR would turn a just-expired token's EXPIRED status into a 404.
     */
    TokenRetentionPurgeJob(QrTokenRepository qrTokens,
                           LoyaltyRefreshTokenRepository refreshTokens,
                           LoyaltyMetrics metrics,
                           TransactionOperations tx,
                           boolean enabled,
                           int qrRetentionDays,
                           int refreshRetentionDays,
                           int batchSize,
                           int maxBatches) {
        this.qrTokens = qrTokens;
        this.refreshTokens = refreshTokens;
        this.metrics = metrics;
        this.tx = tx;
        this.enabled = enabled;
        this.qrRetentionDays = positiveOr(qrRetentionDays, DEFAULT_RETENTION_DAYS);
        this.refreshRetentionDays = positiveOr(refreshRetentionDays, DEFAULT_RETENTION_DAYS);
        this.batchSize = positiveOr(batchSize, DEFAULT_BATCH_SIZE);
        this.maxBatches = positiveOr(maxBatches, DEFAULT_MAX_BATCHES);
    }

    @Scheduled(cron = "${loyalty.retention.purge-cron:0 25 3 * * *}")
    @SchedulerLock(name = "tokenRetentionPurge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        if (!enabled) {
            return;
        }
        run(Instant.now());
    }

    /** One run, as of {@code now}; the test seam behind {@link #purge()}. */
    Outcome run(Instant now) {
        Instant qrCutoff = now.minus(qrRetentionDays, ChronoUnit.DAYS);
        Instant refreshCutoff = now.minus(refreshRetentionDays, ChronoUnit.DAYS);

        TableOutcome qr = drain(LoyaltyMetrics.TABLE_QR_TOKENS, qrCutoff,
                cutoff -> qrTokens.purgeFinishedBefore(cutoff, batchSize));
        TableOutcome refresh = drain(LoyaltyMetrics.TABLE_REFRESH_TOKENS, refreshCutoff,
                cutoff -> refreshTokens.purgeChainsEndedBefore(cutoff, batchSize));

        log.info("Token retention purge: qr_tokens deleted={} (finished before {}, {} batches{}), "
                        + "loyalty_refresh_tokens deleted={} (chains ended before {}, {} batches{})",
                qr.rows(), qrCutoff, qr.batches(), qr.complete() ? "" : ", NOT drained",
                refresh.rows(), refreshCutoff, refresh.batches(), refresh.complete() ? "" : ", NOT drained");
        return new Outcome(qr, refresh);
    }

    private TableOutcome drain(String table, Instant cutoff, ToIntFunction<Instant> deleteBatch) {
        long total = 0;
        int batches = 0;
        try {
            while (batches < maxBatches) {
                Integer deleted = tx.execute(status -> deleteBatch.applyAsInt(cutoff));
                int rows = deleted == null ? 0 : deleted;
                batches++;
                total += rows;
                // Per batch, so rows a later failure leaves committed are still counted.
                metrics.incRetentionPurged(table, rows);
                if (rows < batchSize) {
                    return new TableOutcome(total, batches, true);
                }
            }
            log.warn("Token retention purge: {} hit the {}-batch cap ({} rows); the rest drains on the next run",
                    table, maxBatches, total);
        } catch (RuntimeException e) {
            log.error("Token retention purge: {} failed after {} batches ({} rows deleted)",
                    table, batches, total, e);
        }
        return new TableOutcome(total, batches, false);
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
