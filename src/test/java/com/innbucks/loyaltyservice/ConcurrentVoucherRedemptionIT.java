package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
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
 * Proves voucher redemption is safe under concurrent load. The redemption path
 * uses {@code lockByCode} (SELECT … FOR UPDATE) to serialise threads racing on
 * the same voucher; we fire N parallel redemption attempts at a SINGLE_USE
 * voucher and assert exactly one wins.
 *
 * <p>Runs against Testcontainers Postgres because H2's row-locking semantics
 * are too forgiving — a test that "passes" on H2 can still let two redemptions
 * commit on Postgres. Real DB = real assurance.
 *
 * <p>Requires Docker on the host. If Docker isn't reachable Testcontainers
 * throws on startup and this single test class is skipped; the rest of the
 * suite keeps running.
 */
class ConcurrentVoucherRedemptionIT extends PostgresIntegrationTestBase {

    private static final int CONCURRENCY = 10;

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired VoucherTemplateService voucherTemplateService;
    @Autowired VoucherService voucherService;

    @MockitoBean UserServiceClient userServiceClient;

    @BeforeEach
    void stubUserServiceLookup() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(
                        new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
    }

    @Test
    void exactly_one_thread_wins_when_many_redeem_same_voucher() throws Exception {
        // Seed tenant, merchant, user, template, voucher — commit them so each
        // worker thread sees them in its own transaction.
        Tenant t = new Tenant();
        t.setCode("conc-" + System.nanoTime());
        t.setName("Concurrent Redemption Test");
        final UUID tenantId = tenantRepository.save(t).getId();

        Dtos.MerchantResponse mr = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Race Cafe", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null), new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null)));
        final UUID merchantId = mr.id();

        LoyaltyUser u = userService.findOrEnrol(tenantId, "+263770099911", merchantId);
        final UUID userId = u.getId();

        VoucherTemplate tpl = voucherTemplateService.create(tenantId, merchantId,
                new Dtos.VoucherTemplateRequest(null, "Race off",
                        VoucherTemplate.VoucherType.SINGLE_USE,
                        VoucherTemplate.ValueType.PERCENT,
                        "USD", null, 1, 30, null));

        var issued = voucherService.issue(tenantId,
                new Dtos.IssueVoucherRequest(null, tpl.getId(), new BigDecimal("10"),
                        null, null, userId,
                        Voucher.DeliveryChannel.NONE, null, null, null));
        final String code = issued.code();

        // Fire N parallel redemption attempts. CountDownLatch gates them all at
        // the same moment so they hit the row lock simultaneously.
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicateRejections = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            final int workerIdx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    voucherService.redeem(tenantId, merchantId,
                            new Dtos.RedeemVoucherRequest(null, code, userId,
                                    "OUTLET-RACE", "dev-" + workerIdx, "127.0.0.1"));
                    successes.incrementAndGet();
                } catch (LoyaltyException ex) {
                    if ("ALREADY_REDEEMED".equals(ex.getCode())) {
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
                .as("worker pool did not finish within 30s — likely a lock-up")
                .isTrue();

        // The redemption invariant: a SINGLE_USE voucher can be redeemed exactly once.
        // Postgres's row lock + the uses_remaining decrement enforce this even under
        // 10-thread contention.
        assertThat(successes.get())
                .as("exactly one redemption should succeed")
                .isEqualTo(1);
        assertThat(duplicateRejections.get())
                .as("the other %d threads should fail with ALREADY_REDEEMED", CONCURRENCY - 1)
                .isEqualTo(CONCURRENCY - 1);
        assertThat(otherErrors.get())
                .as("no thread should hit an unexpected exception; got %s", unexpected)
                .isZero();
    }
}
