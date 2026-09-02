package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Pins the TEMPORARY multi-currency pricing guard on the two money paths
 * (design PR 1): the earn and redeem math are still currency-blind, so a
 * non-BASE (non-USD) currency — even one on the cell's allowlist — must be
 * REFUSED before any points arithmetic runs. Without this, a ZWG 500 earn
 * would be credited as if it were $500 (~27× over-credit). PR 2 (earn) and
 * PR 3 (redeem) replace these refusals with fx.toBase(...) conversions; these
 * tests then flip from "refused" to "converted".
 */
class MultiCurrencyGuardTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    /** ZWG is SUPPORTED on this cell — the refusal under test is the pricing
     *  guard, not the allowlist. */
    private static final SupportedCurrencies CURRENCIES =
            new SupportedCurrencies("USD,ZWG", "USD");

    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final WalletService walletService = mock(WalletService.class);
    private final RulesEngine rulesEngine = mock(RulesEngine.class);

    private Merchant merchant(String currency) {
        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        m.setName("Cafe ZWG");
        m.setCurrency(currency);
        m.setStatus(Merchant.Status.ACTIVE);
        return m;
    }

    private LoyaltyUser activeUser() {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(USER);
        u.setTenantId(TENANT);
        u.setPhoneNumber("+263771234567");
        u.setStatus(LoyaltyUser.Status.ACTIVE);
        return u;
    }

    private TransactionService earnService() {
        return new TransactionService(transactions, users, merchants, walletService, rulesEngine,
                new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                new com.innbucks.loyaltyservice.config.LoyaltyProperties(null, null, null, null, null, null, null),
                mock(FraudService.class), mock(StaffRegistry.class), CURRENCIES);
    }

    @SuppressWarnings("unchecked")
    private RedemptionService redeemService() {
        return new RedemptionService(users, merchants, walletService, transactions,
                mock(LoyaltyMetrics.class), mock(RedemptionRateService.class),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                (ObjectProvider<RedemptionService>) mock(ObjectProvider.class), CURRENCIES);
    }

    @Test
    void earn_refusesNonBaseRequestCurrency_beforeThePointsMath() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("USD"));
        when(users.require(TENANT, USER)).thenReturn(activeUser());

        Dtos.TransactionRequest req = new Dtos.TransactionRequest(
                null, USER, null, TransactionType.PURCHASE, new BigDecimal("500"), "ZWG", null);

        assertThatThrownBy(() -> earnService().post(TENANT, MERCHANT, req, null, EarnChannel.QR_PRESENCE))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"))
                .hasMessageContaining("not enabled yet");
        // Fails BEFORE the currency-blind arithmetic — nothing priced, nothing saved.
        verifyNoInteractions(rulesEngine, walletService);
        verify(transactions, never()).save(any());
    }

    @Test
    void earn_refusesNonBaseMerchantDefaultCurrency_whenRequestOmitsIt() {
        // A merchant onboarded in ZWG defaults every earn to ZWG — the guard
        // must catch the defaulted currency, not just an explicit one.
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("ZWG"));
        when(users.require(TENANT, USER)).thenReturn(activeUser());

        Dtos.TransactionRequest req = new Dtos.TransactionRequest(
                null, USER, null, TransactionType.PURCHASE, new BigDecimal("500"), null, null);

        assertThatThrownBy(() -> earnService().post(TENANT, MERCHANT, req, null, EarnChannel.QR_PRESENCE))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
        verifyNoInteractions(rulesEngine, walletService);
    }

    @Test
    void earn_stillAcceptsBaseCurrency() {
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("USD"));
        when(users.require(TENANT, USER)).thenReturn(activeUser());
        when(rulesEngine.evaluate(any(), any(), any(), any()))
                .thenReturn(new RulesEngine.Evaluation(BigDecimal.ZERO, null, null, null));

        Dtos.TransactionRequest req = new Dtos.TransactionRequest(
                null, USER, null, TransactionType.PURCHASE, new BigDecimal("500"), "usd", null);

        // Zero points evaluated → posts without touching the wallet; the point
        // here is only that a USD earn gets PAST the guard.
        earnService().post(TENANT, MERCHANT, req, null, EarnChannel.QR_PRESENCE);
        verify(rulesEngine).evaluate(TENANT, MERCHANT, TransactionType.PURCHASE, new BigDecimal("500"));
    }

    @Test
    void redeem_refusesNonBaseMerchantCurrency_beforeAnyDebit() {
        when(users.require(TENANT, USER)).thenReturn(activeUser());
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(merchant("ZWG"));

        Dtos.RedemptionRequest req = new Dtos.RedemptionRequest(
                MERCHANT, USER, new BigDecimal("100"), "counter redemption", null);

        assertThatThrownBy(() -> redeemService().redeemPoints(TENANT, MERCHANT, req))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"))
                .hasMessageContaining("not enabled yet");
        // Refused before the rate lookup, the ledger row and the wallet debit.
        verifyNoInteractions(walletService);
        verify(transactions, never()).save(any());
        verify(transactions, never()).saveAndFlush(any());
    }
}
