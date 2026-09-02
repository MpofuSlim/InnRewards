package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.ExchangeRate;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * The multi-currency money paths, as of design PR 2 (earn converts) —
 *
 * <ul>
 *   <li><b>Earn</b> now converts the transacted amount to the USD base through
 *       the in-force FX rate and prices points on THAT, freezing both the base
 *       value and the rate row onto the ledger. An unsupported currency is
 *       refused by the allowlist; a supported one with no rate fails closed
 *       with {@code NO_FX_RATE} rather than pricing 1:1.</li>
 *   <li><b>Redeem</b> still refuses any non-USD merchant currency — the redeem
 *       valuation is USD-only until design PR 3. This test is the thing that
 *       will flip when that ships.</li>
 * </ul>
 */
class MultiCurrencyGuardTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();
    private static final UUID ZWG_RATE_ID = UUID.randomUUID();
    private static final String PHONE = "+263771234567";

    private static final SupportedCurrencies CURRENCIES =
            new SupportedCurrencies("USD,ZWG", "USD");

    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final WalletService walletService = mock(WalletService.class);
    private final RulesEngine rulesEngine = mock(RulesEngine.class);
    private final ExchangeRateRepository fxRates = mock(ExchangeRateRepository.class);

    private final ExchangeRateService fx =
            new ExchangeRateService(fxRates, CURRENCIES, new BigDecimal("25"));

    private Merchant merchant(String currency) {
        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        m.setName("Cafe");
        m.setCurrency(currency);
        m.setStatus(Merchant.Status.ACTIVE);
        return m;
    }

    private LoyaltyUser activeUser() {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(USER);
        u.setTenantId(TENANT);
        u.setPhoneNumber(PHONE);
        u.setStatus(LoyaltyUser.Status.ACTIVE);
        return u;
    }

    /** 1 USD = 26.70 ZWG, in force. */
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

    private TransactionService earnService() {
        return new TransactionService(transactions, users, merchants, walletService, rulesEngine,
                new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                new com.innbucks.loyaltyservice.config.LoyaltyProperties(null, null, null, null, null, null, null),
                mock(FraudService.class), mock(StaffRegistry.class), CURRENCIES, fx);
    }

    /**
     * Redemption rate stub standing in for "100 points buys 1 USD" — the
     * platform rate, always read in USD (see RedemptionService). points = usd
     * x 100; usd = points / 100.
     */
    private final RedemptionRateService rateService = mock(RedemptionRateService.class);

    private void usdRedemptionRate() {
        when(rateService.pointsFor(any(), eq("USD"))).thenAnswer(inv ->
                ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("100"))
                        .setScale(0, java.math.RoundingMode.HALF_UP));
        when(rateService.valueOf(any(), eq("USD"))).thenAnswer(inv ->
                ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("100"), 4,
                        java.math.RoundingMode.HALF_UP));
    }

    @SuppressWarnings("unchecked")
    private RedemptionService redeemService() {
        return new RedemptionService(users, merchants, walletService, transactions,
                mock(LoyaltyMetrics.class), rateService,
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                (ObjectProvider<RedemptionService>) mock(ObjectProvider.class), CURRENCIES, fx);
    }

    private Dtos.TransactionRequest purchase(BigDecimal amount, String currency) {
        return new Dtos.TransactionRequest(
                null, USER, null, TransactionType.PURCHASE, amount, currency, null);
    }

    private void earnStubs() {
        when(users.require(TENANT, USER)).thenReturn(activeUser());
        Wallet w = new Wallet();
        w.setId(WALLET);
        when(walletService.mainWallet(PHONE)).thenReturn(w);
        when(walletService.apply(any(), any(), any(), anyString(), any()))
                .thenReturn(new BigDecimal("100"));
    }

    // ---- earn: the conversion ---------------------------------------------

    @Test
    void earn_convertsNonBaseAmountToUsd_beforePricingPoints() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("ZWG"));
        earnStubs();
        zwgRateInForce();
        when(rulesEngine.evaluate(any(), any(), any(), any()))
                .thenReturn(new RulesEngine.Evaluation(new BigDecimal("18"), null, null, null));

        earnService().post(TENANT, MERCHANT, purchase(new BigDecimal("500"), "ZWG"),
                null, EarnChannel.QR_PRESENCE);

        // ZWG 500 / 26.70 = 18.7266 USD — the rules engine must see the USD
        // value, never the 500. (Pricing 500 as dollars is the ~27x over-credit
        // this whole design exists to prevent.)
        verify(rulesEngine).evaluate(TENANT, MERCHANT, TransactionType.PURCHASE,
                new BigDecimal("18.7266"));
    }

    @Test
    void earn_freezesTheBaseValueAndTheRateRowOnTheLedger() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("ZWG"));
        earnStubs();
        zwgRateInForce();
        when(rulesEngine.evaluate(any(), any(), any(), any()))
                .thenReturn(new RulesEngine.Evaluation(new BigDecimal("18"), null, null, null));

        earnService().post(TENANT, MERCHANT, purchase(new BigDecimal("500"), "ZWG"),
                null, EarnChannel.QR_PRESENCE);

        ArgumentCaptor<LoyaltyTransaction> saved = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions).saveAndFlush(saved.capture());
        LoyaltyTransaction t = saved.getValue();
        // What the customer transacted stays as transacted...
        assertThat(t.getAmount()).isEqualByComparingTo("500");
        assertThat(t.getCurrency()).isEqualTo("ZWG");
        // ...and the USD value points were awarded on is frozen alongside it,
        // with the receipt for the conversion.
        assertThat(t.getBaseAmount()).isEqualByComparingTo("18.7266");
        assertThat(t.getFxRateId()).isEqualTo(ZWG_RATE_ID);
    }

    @Test
    void earn_baseCurrency_needsNoRateAndStampsNoRateId() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("USD"));
        earnStubs();
        when(rulesEngine.evaluate(any(), any(), any(), any()))
                .thenReturn(new RulesEngine.Evaluation(new BigDecimal("500"), null, null, null));

        earnService().post(TENANT, MERCHANT, purchase(new BigDecimal("500"), "USD"),
                null, EarnChannel.QR_PRESENCE);

        ArgumentCaptor<LoyaltyTransaction> saved = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getBaseAmount()).isEqualByComparingTo("500");
        // USD is identity — no rate row exists or is consulted.
        assertThat(saved.getValue().getFxRateId()).isNull();
        verifyNoInteractions(fxRates);
    }

    @Test
    void earn_supportedCurrencyWithNoRate_failsClosedWithNoFxRate() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("ZWG"));
        earnStubs();
        when(fxRates.currentRate(nullable(UUID.class), anyString(), any(Instant.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> earnService().post(TENANT, MERCHANT,
                purchase(new BigDecimal("500"), "ZWG"), null, EarnChannel.QR_PRESENCE))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("NO_FX_RATE"));
        // Refused before pricing and before any row is written — a missing rate
        // must never fall back to 1:1.
        verifyNoInteractions(rulesEngine);
        verify(transactions, never()).saveAndFlush(any());
    }

    @Test
    void earn_unsupportedCurrency_isRefusedByTheAllowlist() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("USD"));
        earnStubs();

        assertThatThrownBy(() -> earnService().post(TENANT, MERCHANT,
                purchase(new BigDecimal("500"), "GBP"), null, EarnChannel.QR_PRESENCE))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
        verifyNoInteractions(rulesEngine);
    }

    // ---- redeem: the conversion (design PR 3) ------------------------------

    private void redeemStubs() {
        when(users.require(TENANT, USER)).thenReturn(activeUser());
        Wallet w = new Wallet();
        w.setId(WALLET);
        when(walletService.mainWallet(PHONE)).thenReturn(w);
        when(walletService.apply(any(), any(), any(), anyString(), any()))
                .thenReturn(new BigDecimal("42"));
        usdRedemptionRate();
    }

    @Test
    void redeem_byLocalAmount_convertsToUsdBeforeApplyingTheRedemptionRate() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("ZWG"));
        redeemStubs();
        zwgRateInForce();

        // The till wants ZWG 267.00 off the bill. That is USD 10.00 at 26.70,
        // and at 100 points/USD it costs the customer 1000 points. Pricing the
        // 267 as dollars would have burned 26,700.
        redeemService().redeemPoints(TENANT, MERCHANT, new Dtos.RedemptionRequest(
                MERCHANT, USER, null, "counter redemption", null, new BigDecimal("267.00")));

        verify(rateService).pointsFor(new BigDecimal("10.0000"), "USD");
        verify(walletService).apply(eq(WALLET), eq(new BigDecimal("1000").negate()),
                any(), anyString(), eq(TENANT));
    }

    @Test
    void redeem_freezesUsdLiabilityAndLocalValueAndTheRate() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("ZWG"));
        redeemStubs();
        zwgRateInForce();

        redeemService().redeemPoints(TENANT, MERCHANT, new Dtos.RedemptionRequest(
                MERCHANT, USER, new BigDecimal("1000"), "counter redemption", null));

        ArgumentCaptor<LoyaltyTransaction> saved = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions).save(saved.capture());
        LoyaltyTransaction t = saved.getValue();
        // 1000 points = USD 10.00 of liability = ZWG 267.00 off the customer's bill.
        assertThat(t.getBaseAmount()).isEqualByComparingTo("10.0000");
        assertThat(t.getAmount()).isEqualByComparingTo("267.0000");
        assertThat(t.getCurrency()).isEqualTo("ZWG");
        assertThat(t.getFxRateId()).isEqualTo(ZWG_RATE_ID);
    }

    @Test
    void redeem_requestCurrencyOverridesTheMerchantDefault() {
        // A USD merchant honouring a ZWG-denominated discount.
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("USD"));
        redeemStubs();
        zwgRateInForce();

        redeemService().redeemPoints(TENANT, MERCHANT, new Dtos.RedemptionRequest(
                MERCHANT, USER, null, "counter redemption", null,
                new BigDecimal("267.00"), "ZWG"));

        verify(rateService).pointsFor(new BigDecimal("10.0000"), "USD");
    }

    @Test
    void redeem_usdMerchant_isUnchangedAndStampsNoRate() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("USD"));
        redeemStubs();

        redeemService().redeemPoints(TENANT, MERCHANT, new Dtos.RedemptionRequest(
                MERCHANT, USER, new BigDecimal("1000"), "counter redemption", null));

        ArgumentCaptor<LoyaltyTransaction> saved = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("10.0000");
        assertThat(saved.getValue().getBaseAmount()).isEqualByComparingTo("10.0000");
        assertThat(saved.getValue().getFxRateId()).isNull();
        verifyNoInteractions(fxRates);
    }

    @Test
    void redeem_supportedCurrencyWithNoRate_failsClosedBeforeAnyDebit() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("ZWG"));
        when(users.require(TENANT, USER)).thenReturn(activeUser());
        usdRedemptionRate();
        when(fxRates.currentRate(nullable(UUID.class), anyString(), any(Instant.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> redeemService().redeemPoints(TENANT, MERCHANT,
                new Dtos.RedemptionRequest(MERCHANT, USER, new BigDecimal("1000"),
                        "counter redemption", null)))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("NO_FX_RATE"));
        verify(walletService, never()).apply(any(), any(), any(), anyString(), any());
        verify(transactions, never()).save(any());
    }

    @Test
    void redeem_unsupportedCurrency_isRefusedByTheAllowlist() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("USD"));
        when(users.require(TENANT, USER)).thenReturn(activeUser());

        assertThatThrownBy(() -> redeemService().redeemPoints(TENANT, MERCHANT,
                new Dtos.RedemptionRequest(MERCHANT, USER, new BigDecimal("1000"),
                        "counter redemption", null, null, "GBP")))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
        verify(transactions, never()).save(any());
    }
}
