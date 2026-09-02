package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.exception.LoyaltyException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the supported-currency allowlist contract (multi-currency PR 1): the
 * effective set is configured-list ∪ BASE ∪ cell currency, resolution
 * normalizes case/whitespace and defaults blank to the base, anything outside
 * the set fails CLOSED with {@code UNSUPPORTED_CURRENCY}, and the temporary
 * {@link SupportedCurrencies#requireBaseFor} pricing guard refuses even
 * supported non-base currencies until FX pricing ships (design PRs 2-3).
 */
class SupportedCurrenciesTest {

    @Test
    void defaultConfig_isUsdOnly() {
        SupportedCurrencies c = new SupportedCurrencies("USD", "USD");
        assertThat(c.supported()).containsExactlyInAnyOrder("USD");
    }

    @Test
    void configuredList_isSplitTrimmedAndUppercased() {
        SupportedCurrencies c = new SupportedCurrencies(" usd, zar ,zwg ", "USD");
        assertThat(c.supported()).containsExactlyInAnyOrder("USD", "ZAR", "ZWG");
    }

    @Test
    void cellCurrency_isAlwaysInTheSet_withoutExtraConfig() {
        // A KE cell with INNBUCKS_CURRENCY=KES keeps working without touching
        // LOYALTY_SUPPORTED_CURRENCIES.
        SupportedCurrencies c = new SupportedCurrencies("USD", "kes");
        assertThat(c.supported()).containsExactlyInAnyOrder("USD", "KES");
    }

    @Test
    void baseIsAlwaysPresent_evenWhenConfigOmitsIt() {
        SupportedCurrencies c = new SupportedCurrencies("ZWG", "ZWG");
        assertThat(c.supported()).contains("USD", "ZWG");
    }

    @Test
    void normalize_blankAndNull_defaultToBase() {
        SupportedCurrencies c = new SupportedCurrencies("USD", "USD");
        assertThat(c.normalize(null)).isEqualTo("USD");
        assertThat(c.normalize("  ")).isEqualTo("USD");
        assertThat(c.normalize(" zwg ")).isEqualTo("ZWG");
    }

    @Test
    void requireSupported_acceptsMembers_normalized() {
        SupportedCurrencies c = new SupportedCurrencies("USD,ZAR,ZWG", "USD");
        assertThat(c.requireSupported(" zar ")).isEqualTo("ZAR");
        assertThat(c.requireSupported(null)).isEqualTo("USD"); // blank → base
    }

    @Test
    void requireSupported_refusesUnknownCode_failClosed() {
        SupportedCurrencies c = new SupportedCurrencies("USD,ZAR,ZWG", "USD");
        assertThatThrownBy(() -> c.requireSupported("GBP"))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"))
                .hasMessageContaining("GBP")
                .hasMessageContaining("not supported on this cell");
    }

    @Test
    void requireBaseFor_passesBase() {
        SupportedCurrencies c = new SupportedCurrencies("USD,ZWG", "USD");
        assertThat(c.requireBaseFor("usd", "earn")).isEqualTo("USD");
        assertThat(c.requireBaseFor(null, "earn")).isEqualTo("USD");
    }

    @Test
    void requireBaseFor_refusesSupportedNonBase_untilFxPricingShips() {
        // The temporary rollout guard: ZWG is in the allowlist, but the earn/
        // redeem math is still currency-blind, so a ZWG amount must be refused
        // rather than priced as if it were dollars.
        SupportedCurrencies c = new SupportedCurrencies("USD,ZWG", "USD");
        assertThatThrownBy(() -> c.requireBaseFor("ZWG", "earn"))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"))
                .hasMessageContaining("not enabled yet");
    }

    @Test
    void requireBaseFor_refusesUnknownCode_withTheAllowlistMessage() {
        SupportedCurrencies c = new SupportedCurrencies("USD,ZWG", "USD");
        assertThatThrownBy(() -> c.requireBaseFor("GBP", "redeem"))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"))
                .hasMessageContaining("not supported on this cell");
    }
}
