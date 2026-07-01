package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.UserService;
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
 * Verifies the V5 partial unique index {@code uq_txn_merchant_reference} actually
 * rejects concurrent duplicate (merchant_id, reference) inserts. The Java pre-check
 * in {@code TransactionService.post} narrows the race window; the DB constraint
 * closes it.
 *
 * <p>Runs on Postgres via Testcontainers because H2 doesn't faithfully enforce
 * partial unique indexes — a test passing on H2 could still let duplicates
 * commit on the real DB.
 *
 * <p>Requires Docker on the host.
 */
class DuplicateTransactionReferenceIT extends PostgresIntegrationTestBase {

    private static final int CONCURRENCY = 8;
    private static final String REFERENCE = "POS-RACE-0001";

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;

    @MockitoBean UserServiceClient userServiceClient;

    @BeforeEach
    void stubUserServiceLookup() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(
                        new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
    }

    @Test
    void concurrent_duplicate_references_collapse_to_one_success() throws Exception {
        Tenant t = new Tenant();
        t.setCode("dup-ref-" + System.nanoTime());
        t.setName("Duplicate Ref Test");
        final UUID tenantId = tenantRepository.save(t).getId();

        Dtos.MerchantResponse mr = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Dup Cafe", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null), new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null)));
        final UUID merchantId = mr.id();

        ruleAdminService.createRule(tenantId, merchantId,
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        LoyaltyUser u = userService.findOrEnrol(tenantId, "+263770099922", merchantId);
        final UUID userId = u.getId();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicateRejections = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    transactionService.post(tenantId, merchantId,
                            new Dtos.TransactionRequest(null, userId, null, TransactionType.PURCHASE,
                                    new BigDecimal("100"), "USD", REFERENCE));
                    successes.incrementAndGet();
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

        // Exactly one of the N concurrent POSTs with the same reference wins.
        // The rest hit either the Java pre-check OR the V5 unique constraint
        // (caught + rethrown as 409 in TransactionService.post) — either way
        // it's DUPLICATE_REFERENCE, never a silent double-credit.
        assertThat(successes.get())
                .as("exactly one POST with the duplicate reference should succeed")
                .isEqualTo(1);
        assertThat(duplicateRejections.get())
                .as("the other %d should reject with DUPLICATE_REFERENCE", CONCURRENCY - 1)
                .isEqualTo(CONCURRENCY - 1);
        assertThat(otherErrors.get())
                .as("no unexpected errors; got %s", unexpected)
                .isZero();
    }
}
