package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.ExchangeRate;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Pins the template-less issue path (V45) around money:
 *
 * <ul>
 *   <li>the USD liability freeze (V38) — the USD worth of a voucher is pinned
 *       when it is ISSUED, and since V45 every voucher is a money AMOUNT so the
 *       conversion is unconditional;</li>
 *   <li>currency resolution — explicit request currency wins, absent inherits
 *       the merchant's, anything outside the allowlist fails closed;</li>
 *   <li>the SINGLE_USE / MULTI_USE usage-limit contract;</li>
 *   <li>expiry resolved from the loyalty rules, not from a request field.</li>
 * </ul>
 */
class VoucherLiabilityFreezeTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID ZWG_RATE_ID = UUID.randomUUID();

    private static final SupportedCurrencies CURRENCIES =
            new SupportedCurrencies("USD,ZWG", "USD");

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final MerchantAuthz merchantAuthz = mock(MerchantAuthz.class);
    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
    private final ExchangeRateRepository fxRates = mock(ExchangeRateRepository.class);
    private final ExchangeRateService fx =
            new ExchangeRateService(fxRates, CURRENCIES, new BigDecimal("25"));

    private VoucherService service;

    @BeforeEach
    void setUp() {
        LoyaltyProperties props = mock(LoyaltyProperties.class, RETURNS_DEEP_STUBS);
        when(props.voucher().secret()).thenReturn("test-voucher-secret-value");
        when(props.voucher().defaultValidityDays()).thenReturn(365);
        service = new VoucherService(vouchers, mock(VoucherBatchRepository.class),
                mock(VoucherRedemptionRepository.class),
                mock(MerchantService.class), merchantAuthz, CURRENCIES, rules,
                mock(LoyaltyUserRepository.class),
                mock(UserService.class), mock(NotificationGateway.class),
                mock(FraudService.class), new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                props, fx);
        when(vouchers.findByCode(anyString())).thenReturn(Optional.empty());
        when(rules.findApplicable(eq(TENANT), eq(MERCHANT), eq(TransactionType.PURCHASE)))
                .thenReturn(List.of());
        merchant("USD");
    }

    private void merchant(String currency) {
        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        m.setName("Test Merchant");
        m.setCurrency(currency);
        when(merchantAuthz.requireCallerAdministersMerchant(TENANT, MERCHANT)).thenReturn(m);
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

    private static Dtos.IssueVoucherRequest request(BigDecimal value, String currency) {
        return new Dtos.IssueVoucherRequest(MERCHANT, null, value, currency, null,
                null, null, null, null, null);
    }

    private Voucher issued(BigDecimal value, String currency) {
        service.issue(TENANT, request(value, currency));
        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers).save(cap.capture());
        return cap.getValue();
    }

    // ------------------------------------------------------------------
    // Liability freeze
    // ------------------------------------------------------------------

    @Test
    void amountVoucherInNonBaseCurrency_freezesUsdWorthAndTheRate() {
        zwgRateInForce();

        Voucher v = issued(new BigDecimal("267.00"), "ZWG");

        // ZWG 267.00 / 26.70 = USD 10.0000 of liability, pinned at issue.
        assertThat(v.getValue()).isEqualByComparingTo("267.00");
        assertThat(v.getCurrency()).isEqualTo("ZWG");
        assertThat(v.getBaseValue()).isEqualByComparingTo("10.0000");
        assertThat(v.getFxRateId()).isEqualTo(ZWG_RATE_ID);
    }

    @Test
    void amountVoucherInBaseCurrency_isIdentityAndStampsNoRate() {
        Voucher v = issued(new BigDecimal("5.00"), "USD");

        assertThat(v.getBaseValue()).isEqualByComparingTo("5.0000");
        assertThat(v.getFxRateId()).isNull();
        verifyNoInteractions(fxRates);
    }

    @Test
    void amountVoucherWithNoFxRate_failsClosed_noVoucherIssued() {
        when(fxRates.currentRate(nullable(UUID.class), anyString(), any(Instant.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(TENANT, request(new BigDecimal("267.00"), "ZWG")))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("NO_FX_RATE"));
        // Better to refuse than to hand out a voucher whose cost we cannot state.
        verify(vouchers, never()).save(any());
    }

    @Test
    void responseCarriesTheFrozenLiability() {
        zwgRateInForce();

        Dtos.VoucherResponse resp = service.issue(TENANT, request(new BigDecimal("267.00"), "ZWG"));

        assertThat(resp.value()).isEqualByComparingTo("267.00");
        assertThat(resp.currency()).isEqualTo("ZWG");
        assertThat(resp.baseValue()).isEqualByComparingTo("10.0000");
        assertThat(resp.voucherType()).isEqualTo("SINGLE_USE");
    }

    // ------------------------------------------------------------------
    // Currency resolution — explicit wins, absent inherits, unknown refuses
    // ------------------------------------------------------------------

    @Test
    void currencyDefaultsToTheMerchants() {
        merchant("ZWG");
        zwgRateInForce();

        Voucher v = issued(new BigDecimal("267.00"), null);

        assertThat(v.getCurrency()).isEqualTo("ZWG");
        assertThat(v.getBaseValue()).isEqualByComparingTo("10.0000");
    }

    @Test
    void unsupportedCurrency_failsClosed_noVoucherIssued() {
        assertThatThrownBy(() -> service.issue(TENANT, request(new BigDecimal("5.00"), "GBP")))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
        verify(vouchers, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Value + usage-limit contract (V45: always money, two types only)
    // ------------------------------------------------------------------

    @Test
    void missingOrNonPositiveValue_isRefused() {
        assertThatThrownBy(() -> service.issue(TENANT, request(null, "USD")))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("MISSING_VALUE"));
        assertThatThrownBy(() -> service.issue(TENANT, request(BigDecimal.ZERO, "USD")))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("MISSING_VALUE"));
        verify(vouchers, never()).save(any());
    }

    @Test
    void singleUseWithAConflictingLimit_isRefused() {
        assertThatThrownBy(() -> service.issue(TENANT, new Dtos.IssueVoucherRequest(
                MERCHANT, Voucher.VoucherType.SINGLE_USE, new BigDecimal("5.00"), "USD", 3,
                null, null, null, null, null)))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("USAGE_LIMIT_CONFLICT"));
    }

    @Test
    void multiUseWithoutALimit_isRefused() {
        assertThatThrownBy(() -> service.issue(TENANT, new Dtos.IssueVoucherRequest(
                MERCHANT, Voucher.VoucherType.MULTI_USE, new BigDecimal("5.00"), "USD", null,
                null, null, null, null, null)))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("USAGE_LIMIT_REQUIRED"));
    }

    @Test
    void multiUse_stampsTheTypeAndTheLimit() {
        service.issue(TENANT, new Dtos.IssueVoucherRequest(
                MERCHANT, Voucher.VoucherType.MULTI_USE, new BigDecimal("5.00"), "USD", 3,
                null, null, null, null, null));
        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers).save(cap.capture());

        assertThat(cap.getValue().getVoucherType()).isEqualTo(Voucher.VoucherType.MULTI_USE);
        assertThat(cap.getValue().getUsesRemaining()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Expiry from the rules — merchant rule beats global beats default
    // ------------------------------------------------------------------

    private static LoyaltyRule rule(UUID merchantId, Integer validityDays) {
        LoyaltyRule r = new LoyaltyRule();
        r.setTenantId(TENANT);
        r.setMerchantId(merchantId);
        r.setTransactionType(TransactionType.PURCHASE);
        r.setPointsPerUnit(BigDecimal.ONE);
        r.setActive(true);
        r.setVoucherValidityDays(validityDays);
        return r;
    }

    private static void assertExpiresInDays(Voucher v, int days) {
        Instant expected = Instant.now().plus(days, ChronoUnit.DAYS);
        assertThat(v.getExpiresAt()).isBetween(
                expected.minusSeconds(120), expected.plusSeconds(120));
    }

    @Test
    void expiryDefaultsToThePlatformValidity_whenNoRuleSetsOne() {
        assertExpiresInDays(issued(new BigDecimal("5.00"), "USD"), 365);
    }

    @Test
    void expiryComesFromTheTenantsGlobalRule() {
        when(rules.findApplicable(eq(TENANT), eq(MERCHANT), eq(TransactionType.PURCHASE)))
                .thenReturn(List.of(rule(null, 60)));

        assertExpiresInDays(issued(new BigDecimal("5.00"), "USD"), 60);
    }

    @Test
    void merchantRuleValidity_overridesTheGlobalStandard() {
        when(rules.findApplicable(eq(TENANT), eq(MERCHANT), eq(TransactionType.PURCHASE)))
                .thenReturn(List.of(rule(MERCHANT, 14), rule(null, 60)));

        assertExpiresInDays(issued(new BigDecimal("5.00"), "USD"), 14);
    }

    @Test
    void merchantRuleWithoutValidity_inheritsTheGlobalStandard() {
        when(rules.findApplicable(eq(TENANT), eq(MERCHANT), eq(TransactionType.PURCHASE)))
                .thenReturn(List.of(rule(MERCHANT, null), rule(null, 60)));

        assertExpiresInDays(issued(new BigDecimal("5.00"), "USD"), 60);
    }
}
