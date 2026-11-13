package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.service.partner.PartnerHoldingReconciliation;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the partner holding reconciliation once a day (V70), on cells where the
 * partner voucher API is on. ShedLock keeps two replicas from storing two runs
 * for the same night; a run is a read plus a few inserts, so a lapsed lock
 * costs a duplicate row, never a wrong one.
 *
 * <p>Off switch: {@code LOYALTY_VOUCHER_PARTNER_RECONCILIATION_ENABLED=false}.
 * A failure is logged and counted ({@code outcome=failed}); the next night runs
 * again, and an operator can run it by hand from the admin endpoint.
 */
@Component
public class PartnerHoldingReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PartnerHoldingReconciliationJob.class);

    private final VoucherPartnerProperties props;
    private final PartnerHoldingReconciliation reconciliation;
    private final boolean enabled;

    public PartnerHoldingReconciliationJob(VoucherPartnerProperties props,
                                           PartnerHoldingReconciliation reconciliation,
                                           @Value("${loyalty.voucher-partner.reconciliation.enabled:true}")
                                           boolean enabled) {
        this.props = props;
        this.reconciliation = reconciliation;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${loyalty.voucher-partner.reconciliation.cron:0 17 4 * * *}")
    @SchedulerLock(name = "partnerHoldingReconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        if (!enabled || !props.enabled()) {
            return;
        }
        try {
            reconciliation.run(PartnerHoldingReconciliation.Trigger.SCHEDULED, null);
        } catch (RuntimeException e) {
            // Counted as outcome=failed inside the run.
            log.error("Partner holding reconciliation failed", e);
        }
    }
}
