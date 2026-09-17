package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient;
import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient.CustomerCheckOutcome;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Asks the InnBucks directory, ON DEMAND, whether a phone belongs to a customer
 * — at the moment a PENDING account is about to be refused a spend.
 *
 * <h2>Why this exists</h2>
 * The platform owner's eligibility decision (V44) is that every InnBucks
 * customer may spend loyalty points. Two mechanisms already applied it, and
 * both leave a gap:
 * <ul>
 *   <li>{@code POST /loyalty/partner/registrations} in {@code innbucks_validate}
 *       mode — but it has to be CALLED, which made a customer's first spend
 *       depend on a client remembering to fire a request after sign-in. A client
 *       that forgets, ships late, or drops the response leaves that customer
 *       unable to spend, and nothing here can tell the difference.</li>
 *   <li>{@link com.innbucks.loyaltyservice.scheduler.InnbucksValidateBacklogSweeper}
 *       — needs no client at all, but runs hourly on a bounded sample, so a
 *       customer can wait an hour or more after earning before the points move.</li>
 * </ul>
 * This closes the gap from the backend side: the check runs when loyalty is
 * engaged, so no client is responsible for it and none can skip it.
 *
 * <h2>What it does and does not prove</h2>
 * The directory answers whether a number EXISTS, not who is holding it. So this
 * is the eligibility rule above and nothing more — it is <b>not</b> an ownership
 * proof, and moving the call from a client to here does not make it one. What
 * the placement genuinely buys is that the phone is no longer supplied by a
 * caller: it is read off the loyalty account the spend is already being
 * performed against. Ownership proof remains the OTP → session-exchange path.
 *
 * <h2>Why at the spend gate, and only there</h2>
 * It is the one place the answer changes an outcome, and an already-ACTIVE
 * customer — nearly all of them — never reaches it, so the common path pays
 * nothing. Deliberately NOT on the earn path: {@code findOrCreatePending} runs
 * with a cashier waiting at a till, and an upstream round-trip there would be
 * paid by every first touch of every phone to buy nothing a spend-time check
 * does not already deliver.
 *
 * <h2>The throttle is load-bearing, so no throttle means no check</h2>
 * A spend attempt is caller-triggered and can be repeated, so an unthrottled
 * probe here is a way to make loyalty hammer the InnBucks gateway. Every check
 * therefore claims a per-phone cooldown key in Redis <b>before</b> the call
 * ({@code SETNX}, so two concurrent attempts produce one upstream call), and
 * when no Redis template is available the check is <b>skipped entirely</b>
 * rather than run unthrottled. Skipping costs only latency — the sweeper still
 * converges the same phone — whereas running unthrottled costs a shared
 * upstream. An {@code Unavailable} answer shortens the cooldown, so an outage
 * does not lock a phone out for the full window.
 *
 * <h2>Nothing here throws</h2>
 * This runs inside a customer's spend transaction. A fault in an optimisation
 * must never surface as a failed redemption, so every error path returns
 * {@code false} and the caller falls back to the ordinary
 * {@code USER_PENDING} refusal — the exact behaviour that existed before this
 * class. Same reason an {@code Unavailable} is not a 503: the account really is
 * not spendable yet, and a retryable status would invite a customer to retry
 * something that will not change.
 */
@Component
@Slf4j
public class OnDemandEligibilityCheck {

    private static final String COOLDOWN_PREFIX = "loyalty:ondemand-validate:";

    private final InnbucksCustomerValidateClient validateClient;
    private final LoyaltyMetrics metrics;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean enabled;
    private final Duration cooldown;
    private final Duration unavailableCooldown;

    public OnDemandEligibilityCheck(
            InnbucksCustomerValidateClient validateClient,
            LoyaltyMetrics metrics,
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${loyalty.registration.innbucks-validate.on-demand.enabled:false}") boolean enabled,
            @Value("${loyalty.registration.innbucks-validate.on-demand.cooldown-seconds:900}") long cooldownSeconds,
            @Value("${loyalty.registration.innbucks-validate.on-demand.unavailable-cooldown-seconds:60}")
            long unavailableCooldownSeconds) {
        this.validateClient = validateClient;
        this.metrics = metrics;
        this.redisProvider = redisProvider;
        this.enabled = enabled;
        this.cooldown = Duration.ofSeconds(Math.max(1, cooldownSeconds));
        this.unavailableCooldown = Duration.ofSeconds(Math.max(1, unavailableCooldownSeconds));
    }

    /**
     * @param e164Phone the phone of the loyalty account being spent from —
     *                  always read off that account, never off a request body.
     * @return {@code true} only when the directory positively confirms a
     *         customer on this call. Every other answer — disabled, unprovisioned,
     *         still inside the cooldown, no throttle available, not a customer,
     *         upstream unavailable, or any error at all — is {@code false},
     *         meaning "do not promote", never "not a customer".
     */
    public boolean confirmsCustomer(String e164Phone) {
        if (!enabled || e164Phone == null || e164Phone.isBlank()) {
            return false;
        }
        if (!validateClient.isConfigured()) {
            // Enabled but unprovisioned is already a HALF-PROVISIONED boot ERROR
            // (InnbucksValidateProvisioningCheck); don't also count it per spend.
            return false;
        }
        try {
            if (!claimCooldown(e164Phone)) {
                return false;
            }
            CustomerCheckOutcome outcome = validateClient.checkCustomer(e164Phone);
            if (outcome instanceof InnbucksCustomerValidateClient.Customer) {
                metrics.incOnDemandEligibilityChecked("customer");
                log.info("On-demand eligibility check confirmed a customer for phone={}",
                        MsisdnMasking.mask(e164Phone));
                return true;
            }
            if (outcome instanceof InnbucksCustomerValidateClient.Unavailable u) {
                // Shorten the window: the next attempt should be able to retry
                // soon, because nothing about this phone was actually decided.
                shortenCooldown(e164Phone);
                metrics.incOnDemandEligibilityChecked("unavailable");
                log.warn("On-demand eligibility check could not be answered for phone={} ({})",
                        MsisdnMasking.mask(e164Phone), u.reason());
                return false;
            }
            metrics.incOnDemandEligibilityChecked("not_customer");
            return false;
        } catch (RuntimeException ex) {
            // Never let this fail a spend — the caller's USER_PENDING refusal is
            // the pre-existing behaviour and is always a safe answer.
            metrics.incOnDemandEligibilityChecked("error");
            log.warn("On-demand eligibility check failed for phone={}; falling back to USER_PENDING",
                    MsisdnMasking.mask(e164Phone), ex);
            return false;
        }
    }

    /**
     * @return true when this caller won the right to make the upstream call.
     *         False both when another attempt holds the window and when there is
     *         no Redis to hold one in — see the throttle note on the class.
     */
    private boolean claimCooldown(String e164Phone) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            metrics.incOnDemandEligibilityChecked("no_throttle");
            return false;
        }
        Boolean claimed = redis.opsForValue()
                .setIfAbsent(COOLDOWN_PREFIX + e164Phone, "1", cooldown);
        if (!Boolean.TRUE.equals(claimed)) {
            metrics.incOnDemandEligibilityChecked("cooldown");
            return false;
        }
        return true;
    }

    private void shortenCooldown(String e164Phone) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.expire(COOLDOWN_PREFIX + e164Phone, unavailableCooldown);
        } catch (RuntimeException ex) {
            // The full cooldown then stands. Harmless: the sweeper converges.
            log.debug("Could not shorten the on-demand eligibility cooldown", ex);
        }
    }
}
