package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code loyalty.voucher-partner.*}: the partner voucher API (V62) through which
 * the InnBucks middleware generates, inquires and redeems vouchers for a partner
 * merchant (first: MWOS).
 *
 * <p>Boxed with defaults in the constructor, like {@link VoucherGuardProperties}:
 * a missing primitive would bind as 0, and a limit of 0 refuses every sale.
 *
 * @param enabled               off = the three endpoints answer the unknown-path 404
 * @param audience              the JWS {@code aud} this cell accepts, e.g.
 *                              {@code innbucks-loyalty-vouchers:zw-staging}. Blank refuses every call.
 * @param funding               how a voucher's value reaches holding: {@code NONE} (generate
 *                              refuses) or {@code SIMULATED} (every leg confirms with no money
 *                              moved: STAGING ONLY). The real provider arrives with the BR or
 *                              Veengu integration.
 * @param maxClockSkew          tolerated difference between the partner's clock and ours
 * @param maxLifetime           longest a signed request may live ({@code exp - iat})
 * @param minValue              smallest voucher a partner may generate
 * @param maxValue              largest voucher a partner may generate
 * @param perPhoneDailyCount    vouchers one customer phone may receive per day
 * @param perPhoneDailyValue    total value one customer phone may receive per day
 * @param currencies            comma-separated currencies with a holding account (USD at launch)
 * @param duplicateWindow       same terminal, phone and amount within this is a possible double sale
 * @param codePaymentTtl        how long an InnBucks payment code stays payable
 * @param simulatedDelay        SIMULATED only: how long a "slow" payment takes to resolve
 */
@ConfigurationProperties("loyalty.voucher-partner")
public record VoucherPartnerProperties(Boolean enabled,
                                       String audience,
                                       String funding,
                                       Duration maxClockSkew,
                                       Duration maxLifetime,
                                       BigDecimal minValue,
                                       BigDecimal maxValue,
                                       Integer perPhoneDailyCount,
                                       BigDecimal perPhoneDailyValue,
                                       String currencies,
                                       Duration duplicateWindow,
                                       Duration codePaymentTtl,
                                       Duration simulatedDelay) {

    public static final String FUNDING_NONE = "NONE";
    public static final String FUNDING_SIMULATED = "SIMULATED";

    public VoucherPartnerProperties {
        enabled = enabled != null && enabled;
        audience = audience == null ? "" : audience.strip();
        funding = funding == null || funding.isBlank() ? FUNDING_NONE : funding.strip().toUpperCase(Locale.ROOT);
        maxClockSkew = maxClockSkew == null ? Duration.ofSeconds(30) : maxClockSkew;
        maxLifetime = maxLifetime == null ? Duration.ofSeconds(120) : maxLifetime;
        minValue = minValue == null ? BigDecimal.ONE : minValue;
        maxValue = maxValue == null ? new BigDecimal("500") : maxValue;
        perPhoneDailyCount = perPhoneDailyCount == null ? 10 : perPhoneDailyCount;
        perPhoneDailyValue = perPhoneDailyValue == null ? new BigDecimal("1000") : perPhoneDailyValue;
        currencies = currencies == null || currencies.isBlank() ? "USD" : currencies;
        duplicateWindow = duplicateWindow == null ? Duration.ofMinutes(2) : duplicateWindow;
        codePaymentTtl = codePaymentTtl == null ? Duration.ofMinutes(10) : codePaymentTtl;
        simulatedDelay = simulatedDelay == null ? Duration.ofSeconds(20) : simulatedDelay;
        if (!FUNDING_NONE.equals(funding) && !FUNDING_SIMULATED.equals(funding)) {
            throw new IllegalArgumentException(
                    "loyalty.voucher-partner.funding must be NONE or SIMULATED (was " + funding + ")");
        }
        if (minValue.signum() <= 0 || maxValue.compareTo(minValue) < 0) {
            throw new IllegalArgumentException(
                    "loyalty.voucher-partner.min-value must be positive and not above max-value");
        }
        if (maxLifetime.isNegative() || maxLifetime.isZero() || maxClockSkew.isNegative()) {
            throw new IllegalArgumentException(
                    "loyalty.voucher-partner.max-lifetime must be positive and max-clock-skew not negative");
        }
    }

    /** Defaults everywhere: off, no funding. */
    public static VoucherPartnerProperties defaults() {
        return new VoucherPartnerProperties(null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    public boolean simulatedFunding() {
        return FUNDING_SIMULATED.equals(funding);
    }

    public Set<String> currencySet() {
        return Arrays.stream(currencies.split(","))
                .map(c -> c.strip().toUpperCase(Locale.ROOT))
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
