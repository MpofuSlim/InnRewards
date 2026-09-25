package com.innbucks.loyaltyservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The owner's numbers are the defaults, and a value that would lock everyone out
 * on their first miss (or never let them back in) refuses to boot.
 */
class VoucherGuardPropertiesTest {

    private static VoucherGuardProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("loyalty.voucher.redeem-guard", VoucherGuardProperties.class);
    }

    @Test
    void nothingConfigured_isFivePerMinute_thirtyMinuteLock_on() {
        VoucherGuardProperties p = bind(Map.of());
        assertThat(p.enabled()).isTrue();
        assertThat(p.maxFailures()).isEqualTo(5);
        assertThat(p.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.lockDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.fallbackMaxEntries()).isEqualTo(100_000);
    }

    @Test
    void theEnvStyleValuesBind() {
        VoucherGuardProperties p = bind(Map.of(
                "loyalty.voucher.redeem-guard.max-failures", "3",
                "loyalty.voucher.redeem-guard.window", "PT2M",
                "loyalty.voucher.redeem-guard.lock-duration", "PT15M"));
        assertThat(p.maxFailures()).isEqualTo(3);
        assertThat(p.window()).isEqualTo(Duration.ofMinutes(2));
        assertThat(p.lockDuration()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aZeroThreshold_refusesToBoot() {
        assertThatThrownBy(() -> bind(Map.of("loyalty.voucher.redeem-guard.max-failures", "0")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aZeroOrNegativeDuration_refusesToBoot() {
        assertThatThrownBy(() -> bind(Map.of("loyalty.voucher.redeem-guard.window", "PT0S")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("loyalty.voucher.redeem-guard.lock-duration", "-PT1M")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
