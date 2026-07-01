package com.innbucks.loyaltyservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mirror of user-service CountryMdcConfigTest. */
class CountryMdcConfigTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ZW", "KE", "ZM", "MW", "ZA", "BW", "MZ", "LS", "SZ", "NG"})
    @DisplayName("all ten InnBucks markets boot cleanly")
    void construct_knownCountry_boots(String iso) {
        assertThat(new CountryMdcConfig(iso).getCountry()).isEqualTo(iso);
    }

    @Test
    @DisplayName("lowercase ISO normalises to uppercase")
    void construct_lowercase_normalisedToUpper() {
        assertThat(new CountryMdcConfig("zw").getCountry()).isEqualTo("ZW");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Zimbabwe", "ZN", "US", "ZWE"})
    @DisplayName("unknown / malformed country pin refuses to boot")
    void construct_unknown_throws(String bad) {
        assertThatThrownBy(() -> new CountryMdcConfig(bad)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("null input refuses to boot (no NPE)")
    void construct_null_throws() {
        assertThatThrownBy(() -> new CountryMdcConfig(null)).isInstanceOf(IllegalStateException.class);
    }
}
