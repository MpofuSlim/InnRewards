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

    @SuppressWarnings("unchecked")
    private RedemptionService redeemService() {
        return new RedemptionService(users, merchants, walletService, transactions,
                mock(LoyaltyMetrics.class), mock(RedemptionRateService.class),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                (ObjectProvider<RedemptionService>) mock(ObjectProvider.class), CURRENCIES);
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

    // ---- redeem: still base-only until design PR 3 ------------------------

    @Test
    void redeem_stillRefusesNonBaseMerchantCurrency_beforeAnyDebit() {
        when(users.require(TENANT, USER)).thenReturn(activeUser());
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("ZWG"));

        Dtos.RedemptionRequest req = new Dtos.RedemptionRequest(
                MERCHANT, USER, new BigDecimal("100"), "counter redemption", null);

        assertThatThrownBy(() -> redeemService().redeemPoints(TENANT, MERCHANT, req))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"))
                .hasMessageContaining("not enabled yet");
        verifyNoInteractions(walletService);
        verify(transactions, never()).save(any());
        verify(transactions, never()).saveAndFlush(any());
    }
}
