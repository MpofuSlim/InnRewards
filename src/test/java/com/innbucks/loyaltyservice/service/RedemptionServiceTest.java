package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the caller-ownership guard on {@code /loyalty/redeem}: the JWT-facing
 * overload ({@code enforceCallerOwnership=true}) must block a caller redeeming
 * another user's balance and must never debit; the S2S overload (shop-checkout /
 * ticketing) must skip the check so those trusted internal flows keep working.
 */
class RedemptionServiceTest {

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
            new RedemptionService(users, merchants, walletService, transactions, metrics, rateService, memberNotifier, self,
                    new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"),
                    usdOnlyFx());

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private static Dtos.RedemptionRequest req() {
        return new Dtos.RedemptionRequest(MERCHANT, USER, new BigDecimal("100"), "reason", "ref-1");
    }

    @Test
    void enforceCallerOwnership_blocksNonOwner_andNeverDebits() {
        LoyaltyUser target = new LoyaltyUser();
        target.setId(USER);
        when(users.require(TENANT, USER)).thenReturn(target);
        doThrow(LoyaltyException.forbidden("NOT_WALLET_OWNER", "you can only act on your own loyalty account"))
                .when(users).requireCallerOwnsOrIsAdmin(target);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req(), true))
                .isInstanceOf(LoyaltyException.class);

        // The guard fires before any money moves or any merchant is resolved.
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(merchants, never()).requireMerchant(any(), any());
    }

    @Test
    void s2sOverload_skipsOwnershipCheck() {
        LoyaltyUser target = new LoyaltyUser();
        target.setId(USER);
        when(users.require(TENANT, USER)).thenReturn(target);
        // Short-circuit right after the (skipped) ownership check so we don't have
        // to stub the whole earn path — the assertion is that the S2S overload
        // never consults requireCallerOwnsOrIsAdmin.
        //
        // The stub mirrors what requireSpendable ACTUALLY throws (403). It used to
        // fabricate a 400, which made this read as documentation that USER_PENDING
        // is a bad-request — it isn't, and nothing else pinned the real status.
        // UserServiceTest now owns that contract; this stub just has to stop lying.
        doThrow(LoyaltyException.forbidden("USER_PENDING", "stub — see UserServiceTest for the real message"))
                .when(users).requireSpendable(target);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req()))
                .isInstanceOf(LoyaltyException.class);

        verify(users, never()).requireCallerOwnsOrIsAdmin(any());
    }

    /** Real FX service on a USD-only allowlist: USD converts by identity without
     *  touching the repository, so no stubbing is needed. */
    private static ExchangeRateService usdOnlyFx() {
        return new ExchangeRateService(
                org.mockito.Mockito.mock(com.innbucks.loyaltyservice.repository.ExchangeRateRepository.class),
                new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"),
                new java.math.BigDecimal("25"));
    }

    /**
     * A PENDING customer must be refused on redeem BEFORE anything moves, and
     * with the exact code + status the FE branches on. The wiring is what this
     * pins: {@code requireSpendable} is genuinely on the redeem path (it would
     * be easy to drop while refactoring), and its refusal reaches the caller
     * unchanged rather than being swallowed or remapped.
     *
     * <p>Pending accounts accrue but cannot spend, so a leaked debit here would
     * take points the customer has no way to have authorised yet.
     */
    @Test
    void pendingUser_isRefusedOnRedeem_andNeverDebits() {
        LoyaltyUser target = new LoyaltyUser();
        target.setId(USER);
        target.setStatus(LoyaltyUser.Status.PENDING);
        when(users.require(TENANT, USER)).thenReturn(target);
        doThrow(LoyaltyException.forbidden("USER_PENDING", "Your rewards account is still being set up."))
                .when(users).requireSpendable(target);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req()))
                .isInstanceOfSatisfying(LoyaltyException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo("USER_PENDING");
                    org.assertj.core.api.Assertions.assertThat(ex.getStatus())
                            .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                });

        verify(walletService, never()).apply(any(), any(), any(), any(), any());
        verify(transactions, never()).save(any());
        verify(transactions, never()).saveAndFlush(any());
    }
}
