package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.RedemptionService;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.WalletService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that a transaction can be reversed AT MOST once even under
 * concurrent {@code POST /loyalty/transactions/{id}/reverse} calls — the fix
 * for the double-credit / points-creation bug.
 *
 * <p>We reverse a REDEMPTION (a negative-delta original) so the compensating
 * entry is a CREDIT. That's the dangerous direction: a double reversal invents
 * points and is NOT caught by the wallet's non-negative-balance guard (which
 * only blocks debits). Reversing a positive earn would be masked by that guard,
 * so it wouldn't prove anything.
 *
 * <p>Runs on Postgres via Testcontainers because the guard relies on a
 * row-level pessimistic lock and the {@code uq_txn_reverses_id} partial unique
 * index (V20) — neither of which H2 enforces faithfully.
 *
 * <p>Requires Docker on the host.
 */
class ConcurrentReversalIT extends PostgresIntegrationTestBase {

    private static final int CONCURRENCY = 8;

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired RedemptionService redemptionService;
    @Autowired WalletService walletService;
    @Autowired LoyaltyTransactionRepository transactionRepository;

    @MockitoBean UserServiceClient userServiceClient;

    @BeforeEach
    void stubUserServiceLookup() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(
                        new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
    }

    @Test
    void concurrent_reversals_of_one_transaction_credit_the_wallet_exactly_once() throws Exception {
        Tenant t = new Tenant();
        t.setCode("rev-race-" + System.nanoTime());
        t.setName("Reversal Race Test");
        final UUID tenantId = tenantRepository.save(t).getId();

        Dtos.MerchantResponse mr = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Rev Cafe", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null)));
        final UUID merchantId = mr.id();

        ruleAdminService.createRule(tenantId, merchantId,
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        LoyaltyUser u = userService.findOrEnrol(tenantId, "+263770011223", merchantId);
        final UUID userId = u.getId();

        // Seed a balance: PURCHASE of 300 at 1 pt/unit -> +300 points.
        transactionService.post(tenantId, merchantId,
                new Dtos.TransactionRequest(null, userId, null, TransactionType.PURCHASE,
                        new BigDecimal("300"), "USD", "SEED-EARN-" + System.nanoTime()));

        // Redeem 100 -> a REDEMPTION row (delta -100), balance now 200. This is
        // the transaction we'll hammer with concurrent reversals.
        RedemptionService.RedemptionResult redeem = redemptionService.redeemPoints(tenantId, merchantId,
                new Dtos.RedemptionRequest(null, userId, new BigDecimal("100"), "seed-redeem", null));
        final UUID redemptionTxnId = redeem.transactionId();
        assertThat(walletService.mainWallet("+263770011223").getBalance()).isEqualByComparingTo("200");

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger alreadyReversed = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    transactionService.reverse(tenantId, redemptionTxnId, "concurrent test");
                    successes.incrementAndGet();
                } catch (LoyaltyException ex) {
                    if ("ALREADY_REVERSED".equals(ex.getCode())) {
                        alreadyReversed.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                        synchronized (unexpected) { unexpected.add(ex); }
                    }
                } catch (Throwable other) {
                    otherErrors.incrementAndGet();
                    synchronized (unexpected) { unexpected.add(other); }
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("worker pool did not finish in time")
                .isTrue();

        // Exactly one reversal wins; the rest are rejected as ALREADY_REVERSED.
        assertThat(successes.get())
                .as("exactly one concurrent reversal should succeed")
                .isEqualTo(1);
        assertThat(alreadyReversed.get())
                .as("the other %d should be rejected as ALREADY_REVERSED", CONCURRENCY - 1)
                .isEqualTo(CONCURRENCY - 1);
        assertThat(otherErrors.get())
                .as("no unexpected errors; got %s", unexpected)
                .isZero();

        // The wallet is credited by the single reversal exactly once: 200 + 100.
        // A double reversal would have produced 300+ (points created from nothing).
        assertThat(walletService.mainWallet("+263770011223").getBalance())
                .as("wallet credited by exactly one reversal")
                .isEqualByComparingTo("300");

        // And exactly one compensating ledger row points back at the original.
        long compensating = transactionRepository.findAll().stream()
                .filter(x -> redemptionTxnId.equals(x.getReversesId()))
                .count();
        assertThat(compensating)
                .as("exactly one compensating reversal row should exist")
                .isEqualTo(1);
    }
}
