package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.repository.PointLotRepository;
import com.innbucks.loyaltyservice.service.WalletService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Daily backstop that releases expired point lots (breakage) for wallets that
 * haven't been touched by {@link WalletService#apply} since their lots expired.
 * Active wallets are kept accurate lazily on every credit/debit; this sweep
 * covers idle ones so the outstanding-points liability and balances stay true.
 *
 * <p>Each wallet is expired in its own transaction (so one bad wallet can't roll
 * back the batch) under a row lock, and the operation is idempotent (a lot with
 * remaining=0 is skipped), so a re-run or an overlapping lazy release is safe.
 */
@Component
public class PointExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(PointExpirySweeper.class);
    private static final int BATCH = 500;

    private final PointLotRepository lots;
    private final WalletService walletService;

    public PointExpirySweeper(PointLotRepository lots, WalletService walletService) {
        this.lots = lots;
        this.walletService = walletService;
    }

    @Scheduled(cron = "${loyalty.scheduler.points-expiry-cron:0 15 * * * *}")
    @SchedulerLock(name = "pointExpirySweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void sweep() {
        List<UUID> walletIds = lots.findWalletsWithDueLots(Instant.now(), PageRequest.of(0, BATCH));
        if (walletIds.isEmpty()) return;
        int ok = 0;
        for (UUID walletId : walletIds) {
            try {
                walletService.expireDueLots(walletId); // own transaction + row lock
                ok++;
            } catch (Exception e) {
                log.warn("Point expiry failed for wallet {}: {}", walletId, e.toString());
            }
        }
        log.info("PointExpirySweeper released expired lots for {}/{} wallets", ok, walletIds.size());
    }
}
