package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The redemption formula wired into the burn path: every REDEMPTION row is
 * stamped with the dollar value the platform is liable for (computed at the
 * platform rate), the caller may redeem by CURRENCY amount and have the server
 * decide the points, and a points/amount pair that disagrees is refused.
 *
 * <p>Pure Mockito (no Docker/Spring in this sandbox, per CLAUDE.md).
 */
class RedemptionValuationTest {

    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);
    private final RedemptionRateService rateService = mock(RedemptionRateService.class);
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier =
            mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class);
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<RedemptionService> self =
            mock(org.springframework.beans.factory.ObjectProvider.class);

    private final RedemptionService service =
            new RedemptionService(users, merchants, walletService, transactions, metrics, rateService, memberNotifier, self);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final String PHONE = "+263771234567";

    @BeforeEach
    void happyPathStubs() {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(USER);
        u.setPhoneNumber(PHONE);
        when(users.require(TENANT, USER)).thenReturn(u);

        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setCurrency("USD");
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(m);

        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        w.setPhoneNumber(PHONE);
        when(walletService.mainWallet(PHONE)).thenReturn(w);
        when(walletService.apply(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("50"));
    }

    @Test
    @DisplayName("legacy points-only redeem still works AND now stamps the dollar value")
    void pointsOnlyStampsValue() {
        when(rateService.valueOf(new BigDecimal("250"), "USD")).thenReturn(new BigDecimal("2.5000"));
        // reference null -> the simple save() path (no idempotency flush)
        Dtos.RedemptionRequest req = new Dtos.RedemptionRequest(MERCHANT, USER, new BigDecimal("250"), "reason", null);

        service.redeemPoints(TENANT, MERCHANT, req);

        ArgumentCaptor<LoyaltyTransaction> cap = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions).save(cap.capture());
        LoyaltyTransaction row = cap.getValue();
        assertThat(row.getPointsDelta()).isEqualByComparingTo("-250");
        assertThat(row.getAmount()).as("dollar value stamped for liability").isEqualByComparingTo("2.5000");
        assertThat(row.getCurrency()).isEqualTo("USD");
        // wallet debited by the points, not the dollars
        verify(walletService).apply(any(), eq(new BigDecimal("-250")), any(), any(), eq(TENANT));
    }

    @Test
    @DisplayName("amount-based redeem: server computes the points from the platform rate")
    void amountBasedComputesPoints() {
        when(rateService.pointsFor(new BigDecimal("2.50"), "USD")).thenReturn(new BigDecimal("250"));
        when(rateService.valueOf(new BigDecimal("250"), "USD")).thenReturn(new BigDecimal("2.5000"));
        // points null, amount supplied
        Dtos.RedemptionRequest req =
                new Dtos.RedemptionRequest(MERCHANT, USER, null, "reason", null, new BigDecimal("2.50"));

        service.redeemPoints(TENANT, MERCHANT, req);

        // debited the SERVER-computed points, and the caller never got to pick the number
        verify(walletService).apply(any(), eq(new BigDecimal("-250")), any(), any(), eq(TENANT));
        ArgumentCaptor<LoyaltyTransaction> cap = ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactions).save(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualByComparingTo("2.5000");
    }

    @Test
    @DisplayName("a points/amount pair that disagrees at the current rate is REFUSED, nothing debited")
    void mismatchRefused() {
        when(rateService.pointsFor(new BigDecimal("2.50"), "USD")).thenReturn(new BigDecimal("250"));
        // caller claims 999 points cover $2.50 — server says 250
        Dtos.RedemptionRequest req =
                new Dtos.RedemptionRequest(MERCHANT, USER, new BigDecimal("999"), "reason", null, new BigDecimal("2.50"));

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("does not match");

        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(transactions, never()).save(any());
    }

    @Test
    @DisplayName("neither points nor amount is a clean 400, not an NPE")
    void neitherProvided() {
        Dtos.RedemptionRequest req = new Dtos.RedemptionRequest(MERCHANT, USER, null, "reason", null, null);
        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("greater than zero");
    }
}
