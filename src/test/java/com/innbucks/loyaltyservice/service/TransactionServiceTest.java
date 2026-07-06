package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the A01 per-merchant guard on {@link TransactionService#reverse}: a
 * merchant-scoped caller (SHOP_ADMIN carries a merchantId claim) may only
 * reverse its OWN merchant's transactions, and the guard fires before any
 * money moves. Tenant-admins / SUPER_ADMIN (no merchant claim) bypass, exactly
 * like the voucher-redeem WRONG_MERCHANT check.
 */
class TransactionServiceTest {

    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final RulesEngine rulesEngine = mock(RulesEngine.class);
    private final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);
    private final MemberActivityNotifier memberNotifier = mock(MemberActivityNotifier.class);

    private final TransactionService service = new TransactionService(
            transactions, users, merchants, walletService, rulesEngine, metrics, memberNotifier);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID MERCHANT_B = UUID.randomUUID();

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAsMerchant(UUID merchantId) {
        var auth = new UsernamePasswordAuthenticationToken(
                "caller@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_SHOP_ADMIN")));
        auth.setDetails(new CallerDetails(merchantId, null, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private LoyaltyTransaction original(UUID merchantId, LoyaltyTransaction.Status status) {
        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setId(UUID.randomUUID());
        t.setTenantId(TENANT);
        t.setMerchantId(merchantId);
        t.setUserId(UUID.randomUUID());
        t.setType(TransactionType.PURCHASE);
        t.setPointsDelta(new BigDecimal("100"));
        t.setStatus(status);
        when(transactions.lockById(t.getId())).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void reverse_crossMerchantShopAdmin_isRejected_andNeverDebits() {
        LoyaltyTransaction orig = original(MERCHANT_A, LoyaltyTransaction.Status.POSTED);
        authenticateAsMerchant(MERCHANT_B); // SHOP_ADMIN for a different merchant

        assertThatThrownBy(() -> service.reverse(TENANT, orig.getId(), "refund"))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("WRONG_MERCHANT"));

        // Guard fires before the compensating credit is written or applied.
        assertThat(orig.getStatus()).isEqualTo(LoyaltyTransaction.Status.POSTED);
        verify(transactions, never()).saveAndFlush(any());
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void reverse_ownMerchantShopAdmin_passesGuard() {
        // Same merchant → guard passes. We short-circuit on the ALREADY_REVERSED
        // check right after the (passed) merchant guard so we don't have to stub
        // the whole wallet-credit path — the assertion is that WRONG_MERCHANT did
        // NOT fire for the owning merchant.
        LoyaltyTransaction orig = original(MERCHANT_A, LoyaltyTransaction.Status.REVERSED);
        authenticateAsMerchant(MERCHANT_A);

        assertThatThrownBy(() -> service.reverse(TENANT, orig.getId(), "refund"))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("ALREADY_REVERSED"));
        verify(walletService, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    void reverse_tenantAdmin_noMerchantScope_bypassesGuard() {
        // No merchant claim (MERCHANT_ADMIN / SUPER_ADMIN) → currentMerchantId()
        // is null → guard is skipped; we progress to ALREADY_REVERSED, proving
        // the merchant guard did not block a legitimately tenant-scoped caller.
        LoyaltyTransaction orig = original(MERCHANT_A, LoyaltyTransaction.Status.REVERSED);
        // No authentication set.

        assertThatThrownBy(() -> service.reverse(TENANT, orig.getId(), "refund"))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("ALREADY_REVERSED"));
    }
}
