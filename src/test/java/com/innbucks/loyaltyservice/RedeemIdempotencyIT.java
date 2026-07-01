package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
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
 * Verifies {@code POST /loyalty/redeem} is idempotent on a caller-supplied
 * {@code reference} — the fix for the double-spend bug (a retry must not debit
 * the wallet twice).
 *
 * <p>Two cases:
 * <ul>
 *   <li><b>sequential</b> — a repeat redeem with the same reference replays the
 *       original (same transaction id) and does NOT debit again.</li>
 *   <li><b>concurrent</b> — N simultaneous redeems with the same reference debit
 *       the wallet exactly once; the losers either replay or are rejected with
 *       DUPLICATE_REFERENCE (timing-dependent), but never double-spend.</li>
 * </ul>
 *
 * <p>Runs on Postgres via Testcontainers because the race backstop is the
 * {@code uq_txn_merchant_reference} partial unique index (V16), which H2 doesn't
 * enforce faithfully. Requires Docker on the host.
 */
class RedeemIdempotencyIT extends PostgresIntegrationTestBase {

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

    private UUID tenantId;
    private UUID merchantId;
    private UUID userId;
    private String phone;

    @BeforeEach
    void setUp() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(
                        new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));

        // Unique phone per test method. Wallets are global per phone now, so a
        // shared phone would accumulate balance across @BeforeEach runs (these
        // real-DB ITs don't roll back between methods).
        phone = "+26377" + (System.nanoTime() % 1_000_000_000L);

        Tenant t = new Tenant();
        t.setCode("redeem-idem-" + System.nanoTime());
        t.setName("Redeem Idempotency Test");
        tenantId = tenantRepository.save(t).getId();

        Dtos.MerchantResponse mr = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Idem Cafe", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null)));
        merchantId = mr.id();

        ruleAdminService.createRule(tenantId, merchantId,
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        LoyaltyUser u = userService.findOrEnrol(tenantId, phone, merchantId);
        userId = u.getId();

        // Seed 1000 points so there's plenty to (attempt to) redeem.
        transactionService.post(tenantId, merchantId,
                new Dtos.TransactionRequest(null, userId, null, TransactionType.PURCHASE,
                        new BigDecimal("1000"), "USD", "SEED-EARN-" + System.nanoTime()));
        assertThat(walletService.mainWallet(phone).getBalance()).isEqualByComparingTo("1000");
    }

    @Test
    void sequential_redeem_with_same_reference_replays_and_debits_once() {
        String reference = "BOOKING-" + UUID.randomUUID();

        RedemptionService.RedemptionResult first = redemptionService.redeemPoints(tenantId, merchantId,
                new Dtos.RedemptionRequest(null, userId, new BigDecimal("100"), "redeem", reference));
        assertThat(first.balance()).isEqualByComparingTo("900");

        // Retry with the same reference: replays the original, no second debit.
        RedemptionService.RedemptionResult retry = redemptionService.redeemPoints(tenantId, merchantId,
                new Dtos.RedemptionRequest(null, userId, new BigDecimal("100"), "redeem", reference));

        assertThat(retry.transactionId())
                .as("replay returns the original redemption's id")
                .isEqualTo(first.transactionId());
        assertThat(walletService.mainWallet(phone).getBalance())
                .as("wallet debited exactly once across the retry")
                .isEqualByComparingTo("900");
        assertThat(redemptionRowsFor(reference))
                .as("exactly one REDEMPTION row exists for the reference")
                .isEqualTo(1);
    }

    @Test
    void concurrent_redeem_with_same_reference_debits_wallet_once() throws Exception {
        final String reference = "BOOKING-" + UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger returned = new AtomicInteger();      // winner + replays
        AtomicInteger duplicateRejections = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    redemptionService.redeemPoints(tenantId, merchantId,
                            new Dtos.RedemptionRequest(null, userId, new BigDecimal("100"), "redeem", reference));
                    returned.incrementAndGet();
                } catch (LoyaltyException ex) {
                    if ("DUPLICATE_REFERENCE".equals(ex.getCode())) {
                        duplicateRejections.incrementAndGet();
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

        assertThat(otherErrors.get())
                .as("no unexpected errors; got %s", unexpected)
                .isZero();
        assertThat(returned.get() + duplicateRejections.get())
                .as("every worker either returned a result or was rejected as duplicate")
                .isEqualTo(CONCURRENCY);
        assertThat(returned.get())
                .as("at least the winner returned a result")
                .isGreaterThanOrEqualTo(1);

        // The money invariant: the wallet was debited by EXACTLY one redemption.
        assertThat(walletService.mainWallet(phone).getBalance())
                .as("wallet debited exactly once under concurrent same-reference redeems")
                .isEqualByComparingTo("900");
        assertThat(redemptionRowsFor(reference))
                .as("exactly one REDEMPTION row exists for the reference")
                .isEqualTo(1);
    }

    private long redemptionRowsFor(String reference) {
        return transactionRepository.findAll().stream()
                .filter(x -> x.getType() == TransactionType.REDEMPTION)
                .filter(x -> reference.equals(x.getReference()))
                .count();
    }
}
