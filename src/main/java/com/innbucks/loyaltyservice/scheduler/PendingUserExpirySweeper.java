package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Keeps {@code loyalty_users.status} in line with the phone-level registration
 * fact, in both directions (V40).
 *
 * <p><b>Heal.</b> A PENDING projection whose phone IS registered has fallen
 * behind — minted under a new merchant just after the proof arrived, or already
 * sitting there when an older proof landed. The spend gate heals whichever row
 * the customer actually touches; this converges the rest, so a report or an
 * admin screen never shows a registered customer as pending.
 *
 * <p><b>Age out.</b> A PENDING projection older than
 * {@code loyalty.pending.ttl-days} whose phone is NOT registered flips to
 * INACTIVE, stamped {@code PENDING_EXPIRED} so a later proof can recover it.
 * The accumulated points / vouchers stay in the DB for forensic / reporting
 * purposes — they become unspendable, not erased.
 *
 * <p>The {@code NOT EXISTS} in the age-out query is load-bearing. Before V40
 * this swept on status and age alone, so a customer who had proven their number
 * but whose projection had not caught up could be aged into a state
 * {@code promoteByPhone} deliberately refuses to recover. A phone whose owner
 * has proven it now simply never expires.
 *
 * <p>The sweeper shares its cron with the voucher expiry sweeper because both
 * are housekeeping tasks; a separate cron property could be split off later
 * if their cadences ever diverge.
 */
@Component
public class PendingUserExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingUserExpirySweeper.class);

    private final LoyaltyUserRepository users;
    private final LoyaltyMetrics metrics;
    private final int ttlDays;

    public PendingUserExpirySweeper(LoyaltyUserRepository users,
                                    LoyaltyMetrics metrics,
                                    @Value("${loyalty.pending.ttl-days:90}") int ttlDays) {
        this.users = users;
        this.metrics = metrics;
        this.ttlDays = ttlDays;
    }

    @Scheduled(cron = "${loyalty.scheduler.expiry-cron:0 5 * * * *}")
    @SchedulerLock(name = "pendingUserExpirySweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional
    public void sweep() {
        heal();
        ageOut();
    }

    private void heal() {
        List<LoyaltyUser> stale = users.findPendingButRegistered();
        if (stale.isEmpty()) return;
        for (LoyaltyUser u : stale) {
            u.setStatus(LoyaltyUser.Status.ACTIVE);
            u.setStatusReason(null);
        }
        metrics.incPendingPromoted(stale.size());
        log.info("PendingUserExpirySweeper healed {} PENDING -> ACTIVE (phone already registered)",
                stale.size());
    }

    private void ageOut() {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        List<LoyaltyUser> stale = users.findStaleUnregistered(cutoff);
        if (stale.isEmpty()) return;
        for (LoyaltyUser u : stale) {
            u.setStatus(LoyaltyUser.Status.INACTIVE);
            u.setStatusReason(LoyaltyUser.StatusReason.PENDING_EXPIRED);
        }
        log.info("PendingUserExpirySweeper flipped {} PENDING -> INACTIVE (unregistered, older than {} days)",
                stale.size(), ttlDays);
    }
}
