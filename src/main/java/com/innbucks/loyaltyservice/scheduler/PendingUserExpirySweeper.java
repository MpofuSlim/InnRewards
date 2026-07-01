package com.innbucks.loyaltyservice.scheduler;

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
 * Ages out {@link LoyaltyUser.Status#PENDING} accounts that never registered.
 * After {@code loyalty.pending.ttl-days} (default 90), the row is flipped to
 * {@code INACTIVE} so it can no longer accrue or spend. The accumulated
 * points / vouchers stay in the DB for forensic / reporting purposes — they
 * become unspendable, not erased.
 *
 * <p>The sweeper shares its cron with the voucher expiry sweeper because both
 * are housekeeping tasks; a separate cron property could be split off later
 * if their cadences ever diverge.
 */
@Component
public class PendingUserExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingUserExpirySweeper.class);

    private final LoyaltyUserRepository users;
    private final int ttlDays;

    public PendingUserExpirySweeper(LoyaltyUserRepository users,
                                    @Value("${loyalty.pending.ttl-days:90}") int ttlDays) {
        this.users = users;
        this.ttlDays = ttlDays;
    }

    @Scheduled(cron = "${loyalty.scheduler.expiry-cron:0 5 * * * *}")
    @SchedulerLock(name = "pendingUserExpirySweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        List<LoyaltyUser> stale = users.findByStatusAndCreatedAtBefore(LoyaltyUser.Status.PENDING, cutoff);
        if (stale.isEmpty()) return;
        for (LoyaltyUser u : stale) {
            u.setStatus(LoyaltyUser.Status.INACTIVE);
        }
        log.info("PendingUserExpirySweeper flipped {} PENDING -> INACTIVE (older than {} days)",
                stale.size(), ttlDays);
    }
}
