package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class VoucherExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(VoucherExpirySweeper.class);
    private final VoucherRepository vouchers;

    public VoucherExpirySweeper(VoucherRepository vouchers) {
        this.vouchers = vouchers;
    }

    @Scheduled(cron = "${loyalty.scheduler.expiry-cron:0 5 * * * *}")
    @SchedulerLock(name = "voucherExpirySweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional
    public void sweep() {
        List<Voucher> expired = vouchers.findExpired(Instant.now());
        if (expired.isEmpty()) return;
        for (Voucher v : expired) {
            v.setStatus(Voucher.Status.EXPIRED);
        }
        log.info("VoucherExpirySweeper marked {} vouchers as EXPIRED", expired.size());
    }
}
