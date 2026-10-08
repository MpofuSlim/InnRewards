package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import com.innbucks.loyaltyservice.service.ShopCheckoutService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Several purchases by the SAME customer arriving at once must all earn.
 *
 * <p>Two ways that used to fail, both only under concurrency, and both reported
 * to the shop as a failed checkout for a sale that had happened:
 * <ul>
 *   <li><b>A returning customer.</b> A checkout reads the customer's wallet
 *       (its balance, then its MAIN wallet) before {@code WalletService.apply}
 *       locks it. The lock query then found the wallet already loaded and
 *       compared versions instead of re-reading it, so when another checkout
 *       had committed in between, it threw an optimistic-lock failure instead
 *       of waiting its turn.</li>
 *   <li><b>A first purchase.</b> Two checkouts for a phone loyalty has never
 *       seen both found no customer and both created one; the loser hit the
 *       unique index, which aborts the whole Postgres transaction, so the
 *       "re-read the winner's row" fallback could never run either.</li>
 * </ul>
 *
 * <p>Real Postgres, real threads, each checkout in its own transaction — the
 * same shape as payment-service's {@code /loyalty/internal/shop-checkout}
 * calls. Requires Docker, like every {@link PostgresIntegrationTestBase} test.
 */
class ConcurrentShopCheckoutIT extends PostgresIntegrationTestBase {

    private static final int CONCURRENCY = 8;
    private static final BigDecimal CASH = new BigDecimal("10");

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired ShopRepository shopRepository;
    @Autowired ShopCheckoutService shopCheckoutService;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean UserServiceClient userServiceClient;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void seedShopWithOnePointPerDollar() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));

        Tenant t = new Tenant();
        t.setCode("checkout-race-" + System.nanoTime());
        t.setName("Concurrent Checkout Test");
        tenantId = tenantRepository.save(t).getId();

        UUID merchantId = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Race Grocer", "Retail", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.25"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        ruleAdminService.createRule(tenantId, merchantId,
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        Shop shop = new Shop();
        shop.setTenantId(tenantId);
        shop.setMerchantId(merchantId);
        shop.setName("Race Shop");
        shopId = shopRepository.save(shop).getId();
    }

    @Test
    void concurrentCheckouts_byOneReturningCustomer_allEarn() throws Exception {
        String phone = "+263771000" + String.format("%03d", System.nanoTime() % 1000);
        checkout(phone); // the customer and their wallet now exist

        List<Throwable> failures = checkoutConcurrently(phone);

        assertThat(failures).as("no checkout may fail").isEmpty();
        assertWalletHolds(phone, CASH.multiply(BigDecimal.valueOf(CONCURRENCY + 1L)), CONCURRENCY + 1);
    }

    @Test
    void concurrentFirstPurchases_byANewCustomer_allEarn_andCreateOneCustomer() throws Exception {
        String phone = "+263772000" + String.format("%03d", System.nanoTime() % 1000);

        List<Throwable> failures = checkoutConcurrently(phone);

        assertThat(failures).as("no checkout may fail").isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM loyalty_users WHERE tenant_id = ? AND phone_number = ?",
                Long.class, tenantId, phone)).isEqualTo(1L);
        assertWalletHolds(phone, CASH.multiply(BigDecimal.valueOf(CONCURRENCY)), CONCURRENCY);
    }

    private void checkout(String phone) {
        shopCheckoutService.checkout(shopId, phone, CASH, null, "SHOP-" + UUID.randomUUID());
    }

    /** Fires {@link #CONCURRENCY} checkouts for one phone at the same instant; returns what failed. */
    private List<Throwable> checkoutConcurrently(String phone) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    checkout(phone);
                } catch (Throwable t) {
                    synchronized (failures) { failures.add(t); }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("checkouts finished").isTrue();
        return failures;
    }

    /** One MAIN wallet, the expected balance, and a ledger that agrees with it row for row. */
    private void assertWalletHolds(String phone, BigDecimal expectedBalance, int expectedEarns) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wallets WHERE phone_number = ? AND type = 'MAIN'", Long.class, phone))
                .isEqualTo(1L);
        BigDecimal balance = jdbc.queryForObject(
                "SELECT balance FROM wallets WHERE phone_number = ? AND type = 'MAIN'", BigDecimal.class, phone);
        BigDecimal ledgerSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(l.delta), 0) FROM points_ledger l JOIN wallets w ON w.id = l.wallet_id "
                        + "WHERE w.phone_number = ? AND w.type = 'MAIN'", BigDecimal.class, phone);
        assertThat(balance).isEqualByComparingTo(expectedBalance);
        assertThat(ledgerSum).isEqualByComparingTo(expectedBalance);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM loyalty_transactions t JOIN loyalty_users u ON u.id = t.user_id "
                        + "WHERE u.tenant_id = ? AND u.phone_number = ? AND t.type = 'PURCHASE'",
                Long.class, tenantId, phone)).isEqualTo((long) expectedEarns);
    }
}
