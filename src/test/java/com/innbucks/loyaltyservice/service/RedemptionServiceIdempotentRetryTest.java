package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.exception.RedemptionRaceException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deterministic coverage for {@link RedemptionService#redeemPointsIdempotent} —
 * the retry wrapper that turns a concurrent double-tap into a clean 200 replay.
 * The self-proxy is wired to the instance under test, so the real
 * {@code redeemPoints} runs on each attempt; the two-attempt race is simulated
 * by returning "pre-check miss then hit" and throwing the unique-index violation
 * on the first {@code saveAndFlush}.
 */
class RedemptionServiceIdempotentRetryTest {

    private final UserService users = mock(UserService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final LoyaltyTransactionRepository transactions = mock(LoyaltyTransactionRepository.class);
    private final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);
    private final RedemptionRateService rateService = mock(RedemptionRateService.class);
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier =
            mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RedemptionService> self = mock(ObjectProvider.class);

    private final RedemptionService service =
            new RedemptionService(users, merchants, walletService, transactions, metrics, rateService,
                    memberNotifier, self);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();
    private static final String PHONE = "+263772345678";
    private static final String REF = "BOOKING-1";

    private void stubUserAndMerchant() {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(USER);
        u.setPhoneNumber(PHONE);
        when(users.require(TENANT, USER)).thenReturn(u);
        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setCurrency("USD");
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(m);
        when(rateService.valueOf(any(), eq("USD"))).thenReturn(new BigDecimal("5.0000"));
        // The wrapper re-enters redeemPoints through the proxy — here, the instance itself.
        when(self.getObject()).thenReturn(service);
    }

    private static Dtos.RedemptionRequest req() {
        return new Dtos.RedemptionRequest(MERCHANT, USER, new BigDecimal("100"), "reason", REF);
    }

    @Test
    void race_isRetriedOnce_intoCleanReplay_withoutDoubleDebit() {
        stubUserAndMerchant();

        LoyaltyTransaction winner = new LoyaltyTransaction();
        winner.setId(UUID.randomUUID());
        winner.setType(TransactionType.REDEMPTION);
        // Attempt 1 pre-check misses; attempt 2 (the retry) finds the winner.
        when(transactions.findFirstByMerchantIdAndReference(MERCHANT, REF))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        // Attempt 1 loses the unique-index race at flush.
        when(transactions.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_txn_merchant_reference"));
        // The replay branch reads the current (winner-debited) balance.
        Wallet w = new Wallet();
        w.setId(WALLET);
        w.setBalance(new BigDecimal("900"));
        when(walletService.mainWallet(PHONE)).thenReturn(w);

        RedemptionService.RedemptionResult result =
                service.redeemPointsIdempotent(TENANT, MERCHANT, req(), false);

        // Returns the winner's row + live balance — a clean 200, not a 409.
        assertThat(result.transactionId()).isEqualTo(winner.getId());
        assertThat(result.balance()).isEqualByComparingTo("900");
        // Retried exactly once (pre-check twice), flushed only on the first attempt.
        verify(transactions, times(2)).findFirstByMerchantIdAndReference(MERCHANT, REF);
        verify(transactions, times(1)).saveAndFlush(any());
        // No debit on either attempt: attempt 1 died at flush; attempt 2 replayed.
        verify(walletService, never()).apply(any(), any(), any(), anyString(), any());
    }

    @Test
    void crossTypeConflict_isNotRetried_and409s() {
        stubUserAndMerchant();

        LoyaltyTransaction purchase = new LoyaltyTransaction();
        purchase.setId(UUID.randomUUID());
        purchase.setType(TransactionType.PURCHASE); // reference owned by an earn
        when(transactions.findFirstByMerchantIdAndReference(MERCHANT, REF))
                .thenReturn(Optional.of(purchase));

        assertThatThrownBy(() -> service.redeemPointsIdempotent(TENANT, MERCHANT, req(), false))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> {
                    assertThat(((LoyaltyException) ex).getCode()).isEqualTo("DUPLICATE_REFERENCE");
                    // It is the plain cross-type conflict, NOT the retryable race type.
                    assertThat(ex).isNotInstanceOf(RedemptionRaceException.class);
                });
        // Not retried: pre-check ran once, nothing flushed or debited.
        verify(transactions, times(1)).findFirstByMerchantIdAndReference(MERCHANT, REF);
        verify(transactions, never()).saveAndFlush(any());
        verify(walletService, never()).apply(any(), any(), any(), anyString(), any());
    }

    @Test
    void freshRedeem_throughWrapper_debitsOnce() {
        stubUserAndMerchant();

        when(transactions.findFirstByMerchantIdAndReference(MERCHANT, REF))
                .thenReturn(Optional.empty());
        // saveAndFlush succeeds (no race).
        Wallet w = new Wallet();
        w.setId(WALLET);
        when(walletService.mainWallet(PHONE)).thenReturn(w);
        when(walletService.apply(eq(WALLET), any(), any(), anyString(), eq(TENANT)))
                .thenReturn(new BigDecimal("895"));

        RedemptionService.RedemptionResult result =
                service.redeemPointsIdempotent(TENANT, MERCHANT, req(), false);

        assertThat(result.balance()).isEqualByComparingTo("895");
        // No retry on the happy path: one pre-check, one flush, one debit.
        verify(transactions, times(1)).findFirstByMerchantIdAndReference(MERCHANT, REF);
        verify(transactions, times(1)).saveAndFlush(any());
        verify(walletService, times(1)).apply(eq(WALLET), any(), any(), anyString(), eq(TENANT));
    }
}
