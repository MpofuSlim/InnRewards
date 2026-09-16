package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drains the pre-existing PENDING backlog under the platform owner's
 * eligibility decision (V44): every InnBucks customer may spend loyalty points,
 * so a PENDING phone that the InnBucks directory confirms as a customer is
 * registered ({@code source = INNBUCKS_VALIDATE}) without waiting for that
 * customer to log in through the app.
 *
 * <p>Each run takes a bounded RANDOM sample of unregistered backlog phones
 * (PENDING projections plus {@code PENDING_EXPIRED} age-outs, which
 * {@code registerPhone} recovers), asks the validate client about each, and
 * registers the confirmed customers. Random sampling is what makes coverage
 * converge — see {@code sampleUnregisteredBacklogPhones}. Non-customers are
 * left exactly as they were: still PENDING, still ageing out on the normal
 * clock, re-checked only when a later sample happens to pick them (they may
 * have become customers by then).
 *
 * <p><b>Not {@code @Transactional}, deliberately.</b> Each check is an HTTP
 * round-trip; holding one transaction across a whole batch would pin a
 * connection for minutes and make one late failure roll back every promotion
 * already earned. {@code UserService.registerPhone} opens its own transaction
 * per phone, so each registration commits (and notifies nobody — see below)
 * independently.
 *
 * <p><b>The run ABORTS on the first {@code Unavailable}</b>: an upstream that
 * cannot answer will answer the same way for the rest of the batch, and
 * hammering a struggling gateway with the remainder helps no one. The next
 * scheduled run retries a fresh sample.
 *
 * <p><b>No customer notification from here, deliberately.</b> The endpoint path
 * texts "your points are active" when a login promotes a phone; a bulk backfill
 * doing the same would SMS the entire backlog in one night at real cost and out
 * of any context the customer remembers. If a re-engagement campaign is wanted,
 * that is a marketing decision to take explicitly, not a side effect.
 *
 * <p>Off by default ({@code LOYALTY_INNBUCKS_VALIDATE_SWEEP_ENABLED}); enabling
 * it without provisioning the client logs a HALF-PROVISIONED boot error via
 * {@code InnbucksValidateProvisioningCheck} and each run then no-ops loudly.
 */
@Component
public class InnbucksValidateBacklogSweeper {

    private static final Logger log = LoggerFactory.getLogger(InnbucksValidateBacklogSweeper.class);

    private final LoyaltyUserRepository users;
    private final UserService userService;
    private final InnbucksCustomerValidateClient validateClient;
    private final LoyaltyMetrics metrics;
    private final boolean enabled;
    private final int batchSize;

    public InnbucksValidateBacklogSweeper(LoyaltyUserRepository users,
                                          UserService userService,
                                          InnbucksCustomerValidateClient validateClient,
                                          LoyaltyMetrics metrics,
                                          @Value("${loyalty.registration.innbucks-validate.sweep.enabled:false}") boolean enabled,
                                          @Value("${loyalty.registration.innbucks-validate.sweep.batch-size:100}") int batchSize) {
        this.users = users;
        this.userService = userService;
        this.validateClient = validateClient;
        this.metrics = metrics;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${loyalty.registration.innbucks-validate.sweep.cron:0 20 * * * *}")
    @SchedulerLock(name = "innbucksValidateBacklogSweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    public void sweep() {
        if (!enabled) {
            return;
        }
        if (!validateClient.isConfigured()) {
            // The boot check already ERROR'd; repeat quietly enough not to
            // spam, loudly enough that a log search for the feature finds it.
            log.warn("InnBucks validate backlog sweep is enabled but the validate client is not "
                    + "configured — skipping run. See InnbucksValidateProvisioningCheck.");
            return;
        }
        List<String> phones = users.sampleUnregisteredBacklogPhones(Math.max(1, batchSize));
        if (phones.isEmpty()) {
            return;
        }
        int registered = 0;
        int notCustomer = 0;
        for (String phone : phones) {
            switch (validateClient.checkCustomer(phone)) {
                case InnbucksCustomerValidateClient.Customer ignored -> {
                    UserService.RegistrationResult result = userService.registerPhone(
                            phone, PhoneRegistration.Source.INNBUCKS_VALIDATE, null, null, null);
                    registered++;
                    metrics.incBacklogValidateChecked("customer");
                    log.info("Backlog sweep registered phone={} projectionsPromoted={}",
                            MsisdnMasking.mask(phone), result.projectionsPromoted());
                }
                case InnbucksCustomerValidateClient.NotACustomer r -> {
                    notCustomer++;
                    metrics.incBacklogValidateChecked("not_customer");
                    log.debug("Backlog sweep: phone={} is not an InnBucks customer ({})",
                            MsisdnMasking.mask(phone), r.reason());
                }
                case InnbucksCustomerValidateClient.Unavailable u -> {
                    metrics.incBacklogValidateChecked("unavailable");
                    log.warn("Backlog sweep aborting run: upstream unavailable ({}) after {} of {} "
                                    + "checks (registered={}, notCustomer={}). Next run retries a fresh sample.",
                            u.reason(), registered + notCustomer, phones.size(), registered, notCustomer);
                    return;
                }
            }
        }
        log.info("InnbucksValidateBacklogSweeper checked {} phones: registered={}, notCustomer={}",
                phones.size(), registered, notCustomer);
    }
}
