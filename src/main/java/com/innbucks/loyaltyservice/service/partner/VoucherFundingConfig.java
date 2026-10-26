package com.innbucks.loyaltyservice.service.partner;

import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Picks the partner voucher funding provider (V62) from
 * {@code loyalty.voucher-partner.funding}. SIMULATED logs a WARN at every boot:
 * it issues vouchers with no money behind them and must never run on production.
 */
@Configuration
@Slf4j
public class VoucherFundingConfig {

    @Bean
    public VoucherFundingProvider voucherFundingProvider(VoucherPartnerProperties props) {
        if (props.simulatedFunding()) {
            log.warn("Partner voucher funding is SIMULATED: partner vouchers are issued with NO money moved. "
                    + "Staging only; never set LOYALTY_VOUCHER_PARTNER_FUNDING=SIMULATED on production.");
            return new SimulatedVoucherFundingProvider(props.simulatedDelay(), props.codePaymentTtl(),
                    Clock.systemUTC());
        }
        if (props.enabled()) {
            log.warn("The partner voucher API is enabled with no funding provider (funding=NONE): "
                    + "every generate will be refused until one is configured.");
        }
        return new UnavailableVoucherFundingProvider();
    }
}
