package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Boot-time half-provisioned detector for multi-currency (the ZimSwitch
 * "HALF-PROVISIONED" lesson, applied to FX): a cell that ENABLES a non-base
 * currency (via {@code LOYALTY_SUPPORTED_CURRENCIES}) but has no in-force
 * USD→X rate looks configured while every attempt to price that currency will
 * be refused ({@code NO_FX_RATE}).
 *
 * <p>Deliberately an ERROR log, NOT a boot failure: request-time NO_FX_RATE is
 * the hard guard (fail closed per request), and an enabled-but-unused currency
 * must not brick the cell — the operator preloads the rate and no restart is
 * needed. This runs after the context is up (Flyway has applied V36).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FxProvisioningCheck {

    private final SupportedCurrencies currencies;
    private final ExchangeRateRepository rates;

    @EventListener(ApplicationReadyEvent.class)
    public void checkFxProvisioning() {
        Instant now = Instant.now();
        for (String ccy : currencies.supported()) {
            if (SupportedCurrencies.BASE.equals(ccy)) continue; // base = identity, never needs a rate
            // Platform scope (tenantId null): the inherited default every tenant
            // falls back to. Tenant overrides are optional extras on top of it.
            if (rates.currentRate(null, ccy, now).isEmpty()) {
                log.error("Multi-currency is HALF-PROVISIONED: {} is in the supported-currency set "
                                + "but has NO in-force exchange rate. Every attempt to price {} will be "
                                + "refused (NO_FX_RATE) until a SUPER_ADMIN sets USD→{} via "
                                + "POST /loyalty/exchange-rates.",
                        ccy, ccy, ccy);
            }
        }
    }
}
