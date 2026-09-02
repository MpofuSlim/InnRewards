package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.ExchangeRate;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Pins the voucher liability freeze (multi-currency PR 4 / V38): the USD worth
 * of a money voucher is pinned when it is ISSUED, because that is when the
 * platform makes the promise. Recomputing it later would make the
 * outstanding-voucher book swing on FX alone, with nothing issued and nothing
 * redeemed.
 *
 * <p>The sharp edge this also pins: a PERCENT voucher's value is a PERCENTAGE,
 * not money. "10% off" run through an exchange rate would produce a confident,
 * meaningless liability figure, so non-AMOUNT value types stay null.
 */
class VoucherLiabilityFreezeTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TEMPLATE = UUID.randomUUID();
    private static final UUID ZWG_RATE_ID = UUID.randomUUID();

    private static final SupportedCurrencies CURRENCIES =
            new SupportedCurrencies("USD,ZWG", "USD");

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final VoucherTemplateService templateService = mock(VoucherTemplateService.class);
    private final ExchangeRateRepository fxRates = mock(ExchangeRateRepository.class);
    private final ExchangeRateService fx =
            new ExchangeRateService(fxRates, CURRENCIES, new BigDecimal("25"));

    private VoucherService service;

    @BeforeEach
    void setUp() {
        LoyaltyProperties props = mock(LoyaltyProperties.class, RETURNS_DEEP_STUBS);
        when(props.voucher().secret()).thenReturn("test-voucher-secret-value");
        service = new VoucherService(vouchers, mock(VoucherBatchRepository.class),
                mock(VoucherRedemptionRepository.class), templateService,
                mock(MerchantService.class), mock(LoyaltyUserRepository.class),
                mock(UserService.class), mock(NotificationGateway.class),
                mock(FraudService.class), new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                props, fx);
        when(vouchers.findByCode(anyString())).thenReturn(Optional.empty());
    }

    private void template(VoucherTemplate.ValueType valueType, String currency) {
        VoucherTemplate t = new VoucherTemplate();
        t.setId(TEMPLATE);
        t.setTenantId(TENANT);
        t.setName("Test template");
        t.setValueType(valueType);
        t.setCurrency(currency);
        t.setUsageLimit(1);
        when(templateService.require(TENANT, TEMPLATE)).thenReturn(t);
    }

    private void zwgRateInForce() {
        ExchangeRate r = new ExchangeRate();
        r.setId(ZWG_RATE_ID);
        r.setCurrency("ZWG");
        r.setRatePerUsd(new BigDecimal("26.700000"));
        r.setEffectiveFrom(Instant.now().minusSeconds(3600));
        r.setSource(ExchangeRate.Source.ADMIN);
        when(fxRates.currentRate(nullable(UUID.class), eq("ZWG"), any(Instant.class)))
                .thenReturn(Optional.of(r));
    }

    private Voucher issued(BigDecimal value) {
        service.issue(TENANT, new Dtos.IssueVoucherRequest(
                null, TEMPLATE, value, null, null, null, null, null, null, null));
        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void amountVoucherInNonBaseCurrency_freezesUsdWorthAndTheRate() {
        template(VoucherTemplate.ValueType.AMOUNT, "ZWG");
        zwgRateInForce();

        Voucher v = issued(new BigDecimal("267.00"));

        // ZWG 267.00 / 26.70 = USD 10.0000 of liability, pinned at issue.
        assertThat(v.getValue()).isEqualByComparingTo("267.00");
        assertThat(v.getCurrency()).isEqualTo("ZWG");
        assertThat(v.getBaseValue()).isEqualByComparingTo("10.0000");
        assertThat(v.getFxRateId()).isEqualTo(ZWG_RATE_ID);
    }

    @Test
    void amountVoucherInBaseCurrency_isIdentityAndStampsNoRate() {
        template(VoucherTemplate.ValueType.AMOUNT, "USD");

        Voucher v = issued(new BigDecimal("5.00"));

        assertThat(v.getBaseValue()).isEqualByComparingTo("5.0000");
        assertThat(v.getFxRateId()).isNull();
        verifyNoInteractions(fxRates);
    }

    @Test
    void percentVoucher_isNeverConverted_evenInANonBaseCurrency() {
        // The trap: "10" here means 10 PERCENT. Dividing it by an FX rate would
        // record USD 0.3745 of "liability" for a voucher whose real cost depends
        // entirely on what it is later spent against.
        template(VoucherTemplate.ValueType.PERCENT, "ZWG");
        zwgRateInForce();

        Voucher v = issued(new BigDecimal("10"));

        assertThat(v.getValue()).isEqualByComparingTo("10");
        assertThat(v.getBaseValue()).isNull();
        assertThat(v.getFxRateId()).isNull();
        verifyNoInteractions(fxRates);
    }

    @Test
    void freeItemVoucher_hasNoMoneyValueAndNoLiabilityFigure() {
        template(VoucherTemplate.ValueType.FREE_ITEM, "ZWG");
        zwgRateInForce();

        Voucher v = issued(null);

        assertThat(v.getBaseValue()).isNull();
        assertThat(v.getFxRateId()).isNull();
        verifyNoInteractions(fxRates);
    }

    @Test
    void amountVoucherWithNoFxRate_failsClosed_noVoucherIssued() {
        template(VoucherTemplate.ValueType.AMOUNT, "ZWG");
        when(fxRates.currentRate(nullable(UUID.class), anyString(), any(Instant.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(TENANT, new Dtos.IssueVoucherRequest(
                null, TEMPLATE, new BigDecimal("267.00"), null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("NO_FX_RATE"));
        // Better to refuse than to hand out a voucher whose cost we cannot state.
        verify(vouchers, never()).save(any());
    }

    @Test
    void responseCarriesTheFrozenLiability() {
        template(VoucherTemplate.ValueType.AMOUNT, "ZWG");
        zwgRateInForce();

        Dtos.VoucherResponse resp = service.issue(TENANT, new Dtos.IssueVoucherRequest(
                null, TEMPLATE, new BigDecimal("267.00"), null, null, null, null, null, null, null));

        assertThat(resp.value()).isEqualByComparingTo("267.00");
        assertThat(resp.currency()).isEqualTo("ZWG");
        assertThat(resp.baseValue()).isEqualByComparingTo("10.0000");
    }
}
