package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.service.InvoicingService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class InvoiceScheduler {

    private static final Logger log = LoggerFactory.getLogger(InvoiceScheduler.class);
    private final InvoicingService invoicing;

    public InvoiceScheduler(InvoicingService invoicing) {
        this.invoicing = invoicing;
    }

    @Scheduled(cron = "${loyalty.scheduler.invoice-cron:0 30 1 * * *}")
    @SchedulerLock(name = "invoiceGeneration", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        int created = invoicing.runPeriodicForAllMerchants(LocalDate.now());
        if (created > 0) {
            log.info("InvoiceScheduler created {} invoices", created);
        }
    }
}
