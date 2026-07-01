package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.service.WalletService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Daily integrity check on the loyalty ledger. The rock-solid invariant is that
 * a wallet's cached {@code balance} equals the sum of its {@code points_ledger}
 * deltas — every balance mutation in {@link WalletService} writes a paired
 * ledger entry in the same transaction, so they can only diverge through a bug,
 * a partial failure, or out-of-band tampering. This job finds any drift in a
 * single grouped query and raises it loudly so it's caught here rather than as a
 * customer dispute.
 *
 * <p><b>Detection first.</b> By default the job only alerts (ERROR log +
 * {@code loyalty.reconciliation.drift} metric). Auto-repair — rebuilding the
 * cached balance from the ledger — is gated behind
 * {@code loyalty.reconciliation.auto-fix} (default {@code false}) so a human
 * confirms the ledger is the trustworthy side before any balance is rewritten.
 * Each repair runs in its own transaction under the wallet's row lock, so one
 * failure can't roll back the others.
 *
 * <p>Leader-elected via ShedLock so only one replica runs the scan per tick.
 */
@Component
public class BalanceReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BalanceReconciliationJob.class);

    private final WalletRepository wallets;
    private final WalletService walletService;
    private final LoyaltyMetrics metrics;
    private final boolean autoFix;

    public BalanceReconciliationJob(WalletRepository wallets, WalletService walletService,
                                    LoyaltyMetrics metrics,
                                    @Value("${loyalty.reconciliation.auto-fix:false}") boolean autoFix) {
        this.wallets = wallets;
        this.walletService = walletService;
        this.metrics = metrics;
        this.autoFix = autoFix;
    }

    @Scheduled(cron = "${loyalty.scheduler.reconciliation-cron:0 45 2 * * *}")
    @SchedulerLock(name = "balanceReconciliation", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        List<WalletRepository.BalanceDrift> drifts = wallets.findBalanceDrift();
        if (drifts.isEmpty()) {
            log.debug("Balance reconciliation: no drift across wallets");
            return;
        }

        metrics.incReconciliationDrift(drifts.size());
        int repaired = 0;
        for (WalletRepository.BalanceDrift d : drifts) {
            BigDecimal diff = d.getBalance().subtract(d.getLedgerSum());
            log.error("Balance drift on wallet {}: cached balance {} != ledger sum {} (diff {}) — {}",
                    d.getWalletId(), d.getBalance(), d.getLedgerSum(), diff,
                    autoFix ? "repairing from ledger" : "detection only (loyalty.reconciliation.auto-fix=false)");
            if (autoFix) {
                try {
                    walletService.rebuildBalanceFromLedger(d.getWalletId());
                    repaired++;
                } catch (Exception e) {
                    log.error("Reconciliation repair failed for wallet {}: {}", d.getWalletId(), e.toString());
                }
            }
        }
        if (repaired > 0) metrics.incReconciliationRepaired(repaired);
        log.warn("Balance reconciliation found {} drifting wallet(s); repaired {} (auto-fix {})",
                drifts.size(), repaired, autoFix ? "on" : "off");
    }
}
