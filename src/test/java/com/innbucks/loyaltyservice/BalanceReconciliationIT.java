package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.repository.PointsLedgerRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.scheduler.BalanceReconciliationJob;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.WalletService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Daily balance reconciliation: the invariant {@code wallet.balance ==
 * sum(points_ledger.delta)} is the ledger's integrity guarantee. These tests
 * deliberately corrupt the cached balance out-of-band (a direct
 * {@code save} that bypasses {@link WalletService#apply}, so no ledger entry is
 * written) and prove the job detects the drift, alerts on it, and repairs it
 * only when auto-fix is enabled.
 *
 * <p>Runs on Postgres via Testcontainers; the {@code findBalanceDrift} grouped
 * query (an ad-hoc entity join) is exactly what H2 couldn't fake faithfully.
 * Each test uses a unique phone (wallets are global per phone and these ITs
 * don't roll back between methods) and leaves its wallet consistent so the
 * reused container is never left dirty.
 */
class BalanceReconciliationIT extends PostgresIntegrationTestBase {

    @Autowired com.innbucks.loyaltyservice.repository.TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired PointsLedgerRepository ledgerRepository;
    @Autowired LoyaltyMetrics metrics;
    @Autowired MeterRegistry meterRegistry;
    @Autowired BalanceReconciliationJob jobWithAutoFixOff; // the wired bean: auto-fix defaults to false

    @MockitoBean UserServiceClient userServiceClient;

    private UUID tenantId;
    private UUID merchantId;
    private UUID userId;
    private String phone;

    @BeforeEach
    void setUp() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));

        // Unique but VALID ZW national (9 digits: 78 + 7) so the E.164
        // normaliser accepts it (was an 11-digit national before).
        phone = "+26378" + String.format("%07d", Math.abs(System.nanoTime() % 10_000_000L));

        Tenant t = new Tenant();
        t.setCode("recon-" + System.nanoTime());
        t.setName("Reconciliation Test");
        tenantId = tenantRepository.save(t).getId();

        merchantId = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Recon Cafe", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();

        ruleAdminService.createRule(tenantId, merchantId,
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        LoyaltyUser u = userService.findOrEnrol(tenantId, phone, merchantId);
        userId = u.getId();
    }

    @Test
    void consistent_wallet_is_not_flagged_as_drifting() {
        earn(100, "earn-clean");
        UUID walletId = walletService.mainWallet(phone).getId();

        assertThat(driftFor(walletId)).as("balance == ledger sum -> no drift").isEmpty();
        assertThat(ledgerRepository.sumDeltaByWalletId(walletId)).isEqualByComparingTo("100");
    }

    @Test
    void rebuild_restores_a_balance_that_drifted_from_the_ledger() {
        earn(100, "earn-rebuild");
        UUID walletId = walletService.mainWallet(phone).getId();

        // Corrupt the cached balance out-of-band: a direct save writes no ledger
        // entry, so balance (999) now disagrees with the ledger sum (100).
        corruptBalance(walletId, new BigDecimal("999"));

        WalletRepository.BalanceDrift drift = driftFor(walletId).orElseThrow();
        assertThat(drift.getBalance()).isEqualByComparingTo("999");
        assertThat(drift.getLedgerSum()).isEqualByComparingTo("100");

        BigDecimal rebuilt = walletService.rebuildBalanceFromLedger(walletId);

        assertThat(rebuilt).isEqualByComparingTo("100");
        assertThat(walletRepository.findById(walletId).orElseThrow().getBalance()).isEqualByComparingTo("100");
        assertThat(driftFor(walletId)).as("repaired -> no longer drifting").isEmpty();
    }

    @Test
    void job_alerts_on_drift_and_repairs_only_when_autofix_enabled() {
        earn(50, "earn-job");
        UUID walletId = walletService.mainWallet(phone).getId();
        corruptBalance(walletId, BigDecimal.ZERO); // ledger says 50, cache says 0

        // Detection-only run (the wired bean, auto-fix off) leaves the balance
        // untouched but counts the drift.
        double driftBefore = counter("loyalty.reconciliation.drift");
        jobWithAutoFixOff.reconcile();
        assertThat(counter("loyalty.reconciliation.drift"))
                .as("drift counted").isGreaterThan(driftBefore);
        assertThat(walletRepository.findById(walletId).orElseThrow().getBalance())
                .as("detection-only must NOT mutate the balance").isEqualByComparingTo("0");
        assertThat(driftFor(walletId)).as("still drifting").isPresent();

        // Auto-fix run (manually constructed with the flag on) rebuilds it.
        double repairedBefore = counter("loyalty.reconciliation.repaired");
        BalanceReconciliationJob autoFixJob =
                new BalanceReconciliationJob(walletRepository, walletService, metrics, true);
        autoFixJob.reconcile();

        assertThat(walletRepository.findById(walletId).orElseThrow().getBalance())
                .as("auto-fix rebuilt from the ledger").isEqualByComparingTo("50");
        assertThat(counter("loyalty.reconciliation.repaired"))
                .as("repair counted").isGreaterThan(repairedBefore);
        assertThat(driftFor(walletId)).as("no drift after repair").isEmpty();
    }

    // ---- helpers ----

    private void earn(int amount, String ref) {
        transactionService.post(tenantId, merchantId,
                new Dtos.TransactionRequest(null, userId, null, TransactionType.PURCHASE,
                        new BigDecimal(amount), "USD", ref));
    }

    private void corruptBalance(UUID walletId, BigDecimal bogus) {
        Wallet w = walletRepository.findById(walletId).orElseThrow();
        w.setBalance(bogus);
        walletRepository.saveAndFlush(w);
    }

    private Optional<WalletRepository.BalanceDrift> driftFor(UUID walletId) {
        return walletRepository.findBalanceDrift().stream()
                .filter(d -> d.getWalletId().equals(walletId))
                .findFirst();
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }
}
