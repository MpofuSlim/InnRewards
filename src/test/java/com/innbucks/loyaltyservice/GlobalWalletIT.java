package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Proves the global-wallet model: a customer (one phone) who earns points at
 * DIFFERENT tenants accumulates them in ONE balance, and can spend that balance
 * at any tenant. Points are no longer siloed per tenant.
 *
 * <p>Runs on Postgres via Testcontainers because it exercises the real wallet
 * resolution + the V21 schema (one MAIN wallet per phone). Requires Docker.
 */
class GlobalWalletIT extends PostgresIntegrationTestBase {

    private static final String PHONE = "+263770055667";

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired RedemptionService redemptionService;
    @Autowired WalletService walletService;
    @Autowired WalletRepository wallets;

    @MockitoBean UserServiceClient userServiceClient;

    @BeforeEach
    void stubUserServiceLookup() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(
                        new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
    }

    @Test
    void points_earned_at_one_tenant_are_spendable_at_another_from_one_balance() {
        // Two independent tenants, each with its own merchant + earn rule.
        UUID tenantA = newTenant("A");
        UUID merchantA = newMerchant(tenantA, "Cafe A");
        rule(tenantA, merchantA);

        UUID tenantB = newTenant("B");
        UUID merchantB = newMerchant(tenantB, "Cafe B");
        rule(tenantB, merchantB);

        // Same customer (phone) enrols in both tenants.
        LoyaltyUser userA = userService.findOrEnrol(tenantA, PHONE, merchantA);
        LoyaltyUser userB = userService.findOrEnrol(tenantB, PHONE, merchantB);
        assertThat(userA.getId()).isNotEqualTo(userB.getId()); // distinct per-tenant projections

        // Earn 100 at tenant A and 50 at tenant B.
        transactionService.post(tenantA, merchantA,
                new Dtos.TransactionRequest(null, userA.getId(), null, TransactionType.PURCHASE,
                        new BigDecimal("100"), "USD", "earn-A"));
        transactionService.post(tenantB, merchantB,
                new Dtos.TransactionRequest(null, userB.getId(), null, TransactionType.PURCHASE,
                        new BigDecimal("50"), "USD", "earn-B"));

        // ONE balance holds points earned across both tenants.
        assertThat(walletService.mainWallet(PHONE).getBalance())
                .as("points from both tenants accumulate in one wallet")
                .isEqualByComparingTo("150");

        // Spend at tenant B more than was earned at B — only possible because the
        // tenant-A points are in the same wallet (no per-tenant silo).
        BigDecimal afterRedeem = redemptionService.redeemPoints(tenantB, merchantB,
                new Dtos.RedemptionRequest(null, userB.getId(), new BigDecimal("120"),
                        "spend at B", "gw-redeem-1")).balance();
        assertThat(afterRedeem)
                .as("redeem at B draws the global balance (incl. points earned at A)")
                .isEqualByComparingTo("30");
        assertThat(walletService.mainWallet(PHONE).getBalance()).isEqualByComparingTo("30");

        // Exactly one MAIN wallet exists for the customer.
        long mainWallets = wallets.findByPhoneNumber(PHONE).stream()
                .filter(w -> w.getType() == Wallet.Type.MAIN)
                .count();
        assertThat(mainWallets)
                .as("a customer has exactly one global MAIN wallet")
                .isEqualTo(1);
    }

    private UUID newTenant(String suffix) {
        Tenant t = new Tenant();
        t.setCode("gw-" + suffix + "-" + System.nanoTime());
        t.setName("Global Wallet Test " + suffix);
        return tenantRepository.save(t).getId();
    }

    private UUID newMerchant(UUID tenantId, String name) {
        return merchantService.create(tenantId,
                new Dtos.MerchantRequest(name, "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
    }

    private void rule(UUID tenantId, UUID merchantId) {
        ruleAdminService.createRule(tenantId, merchantId,
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
    }
}
