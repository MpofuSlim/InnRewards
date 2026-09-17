package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.entity.Voucher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transition shim for the retired DELIVERED status (V48).
 *
 * <p>Two properties matter here and pull in opposite directions: the converter
 * must keep the console's existing {@code ?status=DELIVERED} tab working
 * through the deploy window, AND it must not become a second, quieter home for
 * the status that was just retired. So the alias is input-only and metered, and
 * every live value must still bind — because registering a converter for this
 * enum REPLACES Spring's default binding, so a gap here would 400 a valid
 * request that used to work.
 */
class VoucherStatusConverterTest {

    private SimpleMeterRegistry registry;
    private VoucherStatusConverter converter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        converter = new VoucherStatusConverter(new LoyaltyMetrics(registry));
    }

    private double aliasCount() {
        var counter = registry.find("loyalty.voucher.status.legacy_alias").counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    void everyLiveStatusStillBinds() {
        // The whole vocabulary, not just the ones a test happened to name: a
        // value missing from the converter is a 400 on a request that worked
        // before this class existed.
        for (Voucher.Status status : Voucher.Status.values()) {
            assertThat(converter.convert(status.name())).isEqualTo(status);
        }
        assertThat(aliasCount()).isZero();
    }

    @Test
    void retiredDeliveredBindsToIssued_andIsCounted() {
        assertThat(converter.convert("DELIVERED")).isEqualTo(Voucher.Status.ISSUED);
        // Metered so the shim's removal is evidence-based: when this counter
        // flatlines in production, no client still sends the old value.
        assertThat(aliasCount()).isEqualTo(1d);
    }

    @Test
    void bindingIsCaseAndWhitespaceTolerant() {
        assertThat(converter.convert("issued")).isEqualTo(Voucher.Status.ISSUED);
        assertThat(converter.convert("  Redeemed ")).isEqualTo(Voucher.Status.REDEEMED);
        assertThat(converter.convert("delivered")).isEqualTo(Voucher.Status.ISSUED);
    }

    @Test
    void anUnknownStatusStillFails_soTheErrorContractDoesNotMove() {
        // Spring turns this into the same 400 its default enum binder produced.
        assertThatThrownBy(() -> converter.convert("BOGUS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convert(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(aliasCount()).isZero();
    }
}
