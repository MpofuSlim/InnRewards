package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.exception.LoyaltyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The cell's supported-currency allowlist (multi-currency design: USD base +
 * ZAR + ZWG). Currency was previously a free {@code VARCHAR(8)} accepted from
 * requests unvalidated; every write entry point that accepts or defaults a
 * currency now resolves it through here, and anything outside the set FAILS
 * CLOSED with {@code UNSUPPORTED_CURRENCY} — the currency analogue of
 * {@link CountryMdcConfig}'s KNOWN_COUNTRIES refusing an unknown country.
 *
 * <p>The effective set is the configured list
 * ({@code loyalty.currency.supported} / env {@code LOYALTY_SUPPORTED_CURRENCIES},
 * default {@code USD}) UNION the BASE currency UNION the cell's merchant-default
 * currency ({@code innbucks.currency}) — the cell's own default is supported by
 * definition, so a KE cell with {@code INNBUCKS_CURRENCY=KES} keeps working
 * without extra config. The ZW cell sets {@code USD,ZAR,ZWG} at multi-currency
 * go-live.
 *
 * <p>Being IN the set does not make a currency priceable: pricing additionally
 * requires an in-force FX rate, and
 * {@link com.innbucks.loyaltyservice.service.ExchangeRateService} refuses with
 * {@code NO_FX_RATE} when none is configured. Membership here is the first of
 * those two gates, not the whole of it.
 */
@Component
@Slf4j
public class SupportedCurrencies {

    /** The base currency every money value is anchored to. Points are valued in
     *  BASE by the platform redemption rate; FX converts everything else. */
    public static final String BASE = "USD";

    private final Set<String> supported;

    public SupportedCurrencies(
            @Value("${loyalty.currency.supported:USD}") String configured,
            @Value("${innbucks.currency:USD}") String cellCurrency) {
        Set<String> set = new LinkedHashSet<>();
        set.add(BASE);
        if (configured != null) {
            for (String c : configured.split(",")) {
                if (!c.isBlank()) set.add(c.trim().toUpperCase());
            }
        }
        if (cellCurrency != null && !cellCurrency.isBlank()) {
            set.add(cellCurrency.trim().toUpperCase());
        }
        this.supported = Set.copyOf(set);
        log.info("Supported currencies (base {}): {}", BASE, this.supported);
    }

    /** Blank/null → the base currency; otherwise trimmed upper-case. */
    public String normalize(String currency) {
        return (currency == null || currency.isBlank()) ? BASE : currency.trim().toUpperCase();
    }

    public boolean isSupported(String currency) {
        return supported.contains(normalize(currency));
    }

    /**
     * Normalizes and returns the currency, refusing anything outside the
     * allowlist. Fail closed: an unknown code must never reach a money row.
     */
    public String requireSupported(String currency) {
        String ccy = normalize(currency);
        if (!supported.contains(ccy)) {
            throw LoyaltyException.badRequest("UNSUPPORTED_CURRENCY",
                    "Currency " + ccy + " is not supported on this cell. Supported: "
                            + String.join(", ", supported) + ".");
        }
        return ccy;
    }

    public Set<String> supported() {
        return supported;
    }
}
