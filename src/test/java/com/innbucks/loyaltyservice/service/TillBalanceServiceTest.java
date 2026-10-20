package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.RedemptionRate;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The till balance lookup's arithmetic and its read-only promise. The real
 * flows run against Postgres in {@code TillBalanceAndPosReversalIT}; this pins
 * the cases that need a currency the test cell does not rate.
 */
class TillBalanceServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final String PHONE = "+263771234567";

    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final RedemptionRateService rates = mock(RedemptionRateService.class);
    private final ExchangeRateService fx = mock(ExchangeRateService.class);
    private final SupportedCurrencies currencies = mock(SupportedCurrencies.class);
    private final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);
    private final TillBalanceService service =
            new TillBalanceService(users, merchants, wallets, rates, fx, currencies, metrics);

    @BeforeEach
    void stubs() {
        when(users.normalizePhone(anyString())).thenReturn(PHONE);
        RedemptionRate rate = new RedemptionRate();
        rate.setPointsPerUnit(new BigDecimal("100"));
        when(rates.currentRate(SupportedCurrencies.BASE)).thenReturn(rate);
        // The burn's own rule: USD × 100, HALF_UP to whole points.
        when(rates.pointsFor(any(), eq(SupportedCurrencies.BASE))).thenAnswer(inv ->
                ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP));
    }

    private void merchantIn(String currency) {
        Merchant m = new Merchant();
        m.setCurrency(currency);
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(m);
        when(currencies.requireSupported(currency)).thenReturn(currency);
    }

    private void mainWallet(String balance) {
        Wallet w = new Wallet();
        w.setBalance(new BigDecimal(balance));
        when(wallets.findFirstByPhoneNumberAndType(PHONE, Wallet.Type.MAIN)).thenReturn(Optional.of(w));
    }

    private LoyaltyUser account(LoyaltyUser.Status status) {
        LoyaltyUser u = new LoyaltyUser();
        u.setStatus(status);
        u.setPhoneNumber(PHONE);
        when(users.findByPhone(TENANT, PHONE)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void useAll_isRoundedDown_untilTheBurnNeedsNoMoreThanTheBalance() {
        // A round trip through FX that comes back one cent heavy: 10.01 of the
        // local currency converts to 1001 points, more than the 1000 held. The
        // lookup must step down to 10.00, or "use all" is an INSUFFICIENT_FUNDS.
        merchantIn("ZWG");
        mainWallet("1000");
        account(LoyaltyUser.Status.ACTIVE);
        when(fx.fromBase(TENANT, new BigDecimal("10.0000"), "ZWG")).thenReturn(new BigDecimal("10.0149"));
        when(fx.toBase(eq(TENANT), any(), eq("ZWG"))).thenAnswer(inv -> inv.getArgument(1));

        Dtos.TillBalanceResponse r = service.lookup(TENANT, MERCHANT, "0771234567");

        assertThat(r.pointsValue()).isEqualByComparingTo("10.00");
        BigDecimal needed = r.pointsValue().multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP);
        assertThat(needed).isLessThanOrEqualTo(new BigDecimal("1000"));
    }

    @Test
    void anActiveCustomer_inUsd_canSpendTheirWholeMainWallet() {
        merchantIn("USD");
        mainWallet("970");
        account(LoyaltyUser.Status.ACTIVE);
        when(fx.fromBase(TENANT, new BigDecimal("9.7000"), "USD")).thenReturn(new BigDecimal("9.7000"));
        when(fx.toBase(eq(TENANT), any(), eq("USD"))).thenAnswer(inv -> inv.getArgument(1));

        Dtos.TillBalanceResponse r = service.lookup(TENANT, MERCHANT, "0771234567");

        assertThat(r.phoneMasked()).isEqualTo("****4567");
        assertThat(r.points()).isEqualByComparingTo("970");
        assertThat(r.pointsValue()).isEqualByComparingTo("9.70");
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.pointsPerUnit()).isEqualByComparingTo("100");
        assertThat(r.accountStatus()).isEqualTo("ACTIVE");
        assertThat(r.canSpendHere()).isTrue();
    }

    @Test
    void aPhoneWithNoAccountHere_isNone_withNothingCreated() {
        merchantIn("USD");
        when(wallets.findFirstByPhoneNumberAndType(PHONE, Wallet.Type.MAIN)).thenReturn(Optional.empty());
        when(users.findByPhone(TENANT, PHONE)).thenReturn(Optional.empty());

        Dtos.TillBalanceResponse r = service.lookup(TENANT, MERCHANT, "0771234567");

        assertThat(r.points()).isEqualByComparingTo("0");
        assertThat(r.pointsValue()).isEqualByComparingTo("0.00");
        assertThat(r.accountStatus()).isEqualTo("NONE");
        assertThat(r.canSpendHere()).isFalse();
        verify(wallets, never()).save(any());
        verify(users, never()).findOrCreatePending(any(), any(), any());
        verify(users, never()).spendabilityOf(any());
        verify(metrics).incTillBalanceLookup(LoyaltyMetrics.TILL_LOOKUP_NONE);
    }

    @Test
    void aPendingRowWhosePhoneIsRegistered_readsActive_becauseTheBurnHealsIt() {
        merchantIn("USD");
        mainWallet("0");
        LoyaltyUser u = account(LoyaltyUser.Status.PENDING);
        when(users.isRegistrationPending(u)).thenReturn(false);

        assertThat(service.lookup(TENANT, MERCHANT, PHONE).accountStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void anUnregisteredPhone_isPending_andCannotSpend() {
        merchantIn("USD");
        mainWallet("0");
        LoyaltyUser u = account(LoyaltyUser.Status.PENDING);
        when(users.isRegistrationPending(u)).thenReturn(true);

        Dtos.TillBalanceResponse r = service.lookup(TENANT, MERCHANT, PHONE);

        assertThat(r.accountStatus()).isEqualTo("PENDING");
        assertThat(r.canSpendHere()).isFalse();
        verify(users, never()).spendabilityOf(any());
    }

    @Test
    void blockedAndInactive_cannotSpend() {
        merchantIn("USD");
        mainWallet("500");
        when(fx.fromBase(eq(TENANT), any(), eq("USD"))).thenAnswer(inv -> inv.getArgument(1));
        when(fx.toBase(eq(TENANT), any(), eq("USD"))).thenAnswer(inv -> inv.getArgument(1));

        account(LoyaltyUser.Status.BLOCKED);
        assertThat(service.lookup(TENANT, MERCHANT, PHONE).canSpendHere()).isFalse();
        account(LoyaltyUser.Status.INACTIVE);
        assertThat(service.lookup(TENANT, MERCHANT, PHONE).accountStatus()).isEqualTo("INACTIVE");
    }
}
