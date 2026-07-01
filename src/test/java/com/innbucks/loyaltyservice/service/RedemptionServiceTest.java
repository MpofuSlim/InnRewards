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
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier =
            mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class);

    private final RedemptionService service =
            new RedemptionService(users, merchants, walletService, transactions, metrics, memberNotifier);

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
        doThrow(LoyaltyException.badRequest("USER_PENDING", "pending"))
                .when(users).requireSpendable(target);

        assertThatThrownBy(() -> service.redeemPoints(TENANT, MERCHANT, req()))
                .isInstanceOf(LoyaltyException.class);

        verify(users, never()).requireCallerOwnsOrIsAdmin(any());
    }
}
