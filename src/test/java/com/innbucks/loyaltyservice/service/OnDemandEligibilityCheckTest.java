package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the on-demand eligibility check (see {@link OnDemandEligibilityCheck}):
 * <ul>
 *   <li>off by default, and never asks upstream when disabled or unprovisioned;</li>
 *   <li>the per-phone cooldown is claimed BEFORE the call, so a repeated spend
 *       attempt produces one upstream request, not one per attempt;</li>
 *   <li>with no Redis to throttle in it SKIPS rather than asking unthrottled —
 *       the deliberate choice, since the throttle is what stops a
 *       caller-triggered path hammering a shared upstream;</li>
 *   <li>only a positive {@code Customer} answer returns true; a transient
 *       {@code Unavailable} shortens the window and returns false, so an outage
 *       neither promotes anyone nor locks the phone out for the full cooldown —
 *       while a 4xx {@code Unavailable} (this cell's ordinary "not a customer",
 *       see below) serves the full one;</li>
 *   <li><b>nothing throws</b> — this runs inside a customer's spend
 *       transaction, so a fault must degrade to the ordinary refusal.</li>
 * </ul>
 *
 * Pure JUnit + Mockito, per the repo's no-Docker-sandbox convention.
 */
class OnDemandEligibilityCheckTest {

    private static final String PHONE = "+263771234567";
    private static final String KEY = "loyalty:ondemand-validate:" + PHONE;

    private InnbucksCustomerValidateClient client;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SimpleMeterRegistry registry;
    private LoyaltyMetrics metrics;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(InnbucksCustomerValidateClient.class);
        when(client.isConfigured()).thenReturn(true);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        registry = new SimpleMeterRegistry();
        metrics = new LoyaltyMetrics(registry);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate template) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    private OnDemandEligibilityCheck check(boolean enabled, StringRedisTemplate template) {
        return new OnDemandEligibilityCheck(client, metrics, provider(template), enabled, 900, 60);
    }

    /** Cooldown claim succeeds — this caller gets to make the upstream call. */
    private void cooldownIsFree() {
        when(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class))).thenReturn(true);
    }

    private double checks(String outcome) {
        var counter = registry.find("loyalty.registration.ondemand.checked")
                .tag("outcome", outcome).counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    void disabledNeverAsksUpstream() {
        assertThat(check(false, redis).confirmsCustomer(PHONE)).isFalse();

        verify(client, never()).checkCustomer(anyString());
        verify(redis, never()).opsForValue();
    }

    @Test
    void unprovisionedClientNeverAsksUpstream() {
        // Enabled-but-unprovisioned is already a HALF-PROVISIONED boot ERROR;
        // it must not also become an upstream call or a per-spend counter.
        when(client.isConfigured()).thenReturn(false);

        assertThat(check(true, redis).confirmsCustomer(PHONE)).isFalse();

        verify(client, never()).checkCustomer(anyString());
        assertThat(registry.find("loyalty.registration.ondemand.checked").counters()).isEmpty();
    }

    @Test
    void blankPhoneIsRefusedWithoutACall() {
        OnDemandEligibilityCheck check = check(true, redis);

        assertThat(check.confirmsCustomer(null)).isFalse();
        assertThat(check.confirmsCustomer("   ")).isFalse();

        verify(client, never()).checkCustomer(anyString());
    }

    @Test
    void confirmedCustomerReturnsTrue() {
        cooldownIsFree();
        when(client.checkCustomer(PHONE))
                .thenReturn(new InnbucksCustomerValidateClient.Customer("00"));

        assertThat(check(true, redis).confirmsCustomer(PHONE)).isTrue();
        assertThat(checks("customer")).isEqualTo(1d);
    }

    @Test
    void notACustomerReturnsFalseAndKeepsTheFullCooldown() {
        cooldownIsFree();
        when(client.checkCustomer(PHONE))
                .thenReturn(new InnbucksCustomerValidateClient.NotACustomer("http_400"));

        assertThat(check(true, redis).confirmsCustomer(PHONE)).isFalse();
        assertThat(checks("not_customer")).isEqualTo(1d);
        // A decided answer: no reason to re-ask sooner than the full window.
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void aTransientUnavailableShortensTheCooldownAndPromotesNobody() {
        // Nothing about this phone was decided, so the next attempt should be
        // able to retry soon — but it must not be read as "not a customer".
        cooldownIsFree();
        when(client.checkCustomer(PHONE))
                .thenReturn(new InnbucksCustomerValidateClient.Unavailable("connect_refused"));

        assertThat(check(true, redis).confirmsCustomer(PHONE)).isFalse();
        assertThat(checks("unavailable")).isEqualTo(1d);
        verify(redis).expire(KEY, Duration.ofSeconds(60));
    }

    /**
     * The ZW cell's shape, and the reason the shortening is conditional.
     *
     * <p>Its validate-path is overridden to {@code /details}, which answers 400
     * for a number it does not know — so the ordinary "not a customer" arrives
     * here as {@code Unavailable(http_400)}. Shortening the window for it would
     * put the most common negative answer on a 60-second retry rather than the
     * full 15 minutes, a fifteen-fold upstream rate for phones that can never
     * succeed. A 4xx is the platform answering THIS request, so it serves the
     * full cooldown.
     */
    @Test
    void a4xxUnavailableServesTheFullCooldown() {
        cooldownIsFree();
        when(client.checkCustomer(PHONE))
                .thenReturn(new InnbucksCustomerValidateClient.Unavailable("http_400"));

        assertThat(check(true, redis).confirmsCustomer(PHONE)).isFalse();

        assertThat(checks("unavailable")).isEqualTo(1d);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void a5xxOrIoUnavailableStillShortensIt() {
        // These clear on their own, so a real customer must not wait out the
        // full window after the outage ends.
        cooldownIsFree();
        for (String reason : new String[]{"http_503", "io_error", "credentials_rejected",
                "malformed_2xx", "unconfigured"}) {
            when(client.checkCustomer(PHONE))
                    .thenReturn(new InnbucksCustomerValidateClient.Unavailable(reason));

            assertThat(check(true, redis).confirmsCustomer(PHONE)).as(reason).isFalse();
        }
        verify(redis, org.mockito.Mockito.times(5)).expire(KEY, Duration.ofSeconds(60));
    }

    @Test
    void theCooldownIsClaimedBeforeTheCallSoARepeatAttemptCostsNothingUpstream() {
        // The throttle's whole point: a spend attempt is caller-triggered and
        // repeatable, so N attempts must not be N upstream requests.
        when(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class)))
                .thenReturn(true)    // first attempt wins the window
                .thenReturn(false);  // every attempt inside it is throttled
        when(client.checkCustomer(PHONE))
                .thenReturn(new InnbucksCustomerValidateClient.NotACustomer("http_400"));
        OnDemandEligibilityCheck check = check(true, redis);

        assertThat(check.confirmsCustomer(PHONE)).isFalse();
        assertThat(check.confirmsCustomer(PHONE)).isFalse();
        assertThat(check.confirmsCustomer(PHONE)).isFalse();

        verify(client).checkCustomer(PHONE);   // exactly once
        assertThat(checks("cooldown")).isEqualTo(2d);
    }

    @Test
    void noRedisMeansNoCheckAtAll() {
        // Deliberately fail-closed on the OPTIMISATION: skipping costs only
        // latency (the hourly sweeper still converges this phone), whereas
        // running unthrottled costs a shared upstream.
        assertThat(check(true, null).confirmsCustomer(PHONE)).isFalse();

        verify(client, never()).checkCustomer(anyString());
        assertThat(checks("no_throttle")).isEqualTo(1d);
    }

    @Test
    void nothingThrows_soAFaultCanNeverFailASpend() {
        // This runs inside the customer's spend transaction. Both halves can
        // fail independently, and neither may escape.
        when(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        assertThat(check(true, redis).confirmsCustomer(PHONE)).isFalse();
        assertThat(checks("error")).isEqualTo(1d);

        cooldownIsFree();
        when(client.checkCustomer(PHONE)).thenThrow(new IllegalStateException("boom"));
        assertThat(check(true, redis).confirmsCustomer(PHONE)).isFalse();
        assertThat(checks("error")).isEqualTo(2d);
    }
}
