package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.PointLot;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.PointLotRepository;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Lot-based points expiry + breakage: earning opens a lot, redeeming burns lots
 * FIFO, expired lots are released to the ledger (breakage) and cannot be spent.
 *
 * <p>Time is advanced by back-dating a lot's {@code expires_at} (rather than
 * sleeping), then invoking the same release path the daily sweeper uses. Runs on
 * Postgres via Testcontainers; requires Docker. Each test uses a unique phone
 * (wallets are global per phone and these ITs don't roll back between methods).
 */
class PointExpiryIT extends PostgresIntegrationTestBase {

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired RedemptionService redemptionService;
    @Autowired WalletService walletService;
    @Autowired PointLotRepository pointLots;

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
        t.setCode("expiry-" + System.nanoTime());
        t.setName("Points Expiry Test");
        tenantId = tenantRepository.save(t).getId();

        merchantId = merchantService.create(tenantId,
                new Dtos.MerchantRequest("Expiry Cafe", "F&B", "USD",
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
    void earn_opens_a_lot_redeem_burns_it_then_expiry_releases_breakage() {
        earn(100, "earn-1");
        UUID walletId = walletService.mainWallet(phone).getId();

        List<PointLot> lots = pointLots.findByWalletId(walletId);
        assertThat(lots).hasSize(1);
        assertThat(lots.get(0).getRemainingAmount()).isEqualByComparingTo("100");
        assertThat(walletService.mainWallet(phone).getBalance()).isEqualByComparingTo("100");

        // Redeem 30 -> the lot is burned down to 70.
        redeem(30, "redeem-1");
        assertThat(walletService.mainWallet(phone).getBalance()).isEqualByComparingTo("70");
        assertThat(pointLots.findByWalletId(walletId).get(0).getRemainingAmount()).isEqualByComparingTo("70");

        // Age the lot past expiry, then run the release path the sweeper uses.
        backdate(walletId, Instant.now().minus(1, ChronoUnit.DAYS));
        walletService.expireDueLots(walletId);

        assertThat(walletService.mainWallet(phone).getBalance())
                .as("expired remainder is released (breakage)")
                .isEqualByComparingTo("0");
        assertThat(pointLots.findByWalletId(walletId).get(0).getRemainingAmount()).isEqualByComparingTo("0");
    }

    @Test
    void expired_points_cannot_be_spent_and_are_released_by_the_sweep() {
        earn(100, "earn-2");
        UUID walletId = walletService.mainWallet(phone).getId();
        backdate(walletId, Instant.now().minus(1, ChronoUnit.DAYS));

        // The redeem lazily releases the expired lot first, so the spendable
        // balance is 0 and the spend is refused — expired points aren't spendable.
        // (The failed spend rolls back, so the release rolls back with it; the
        // points stay on the books until a successful op or the sweep releases them.)
        assertThatThrownBy(() -> redeem(40, "redeem-2"))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("don't have enough");
        assertThat(walletService.mainWallet(phone).getBalance())
                .as("nothing was spent").isEqualByComparingTo("100");

        // The sweep (its own committed transaction) releases the expired points.
        walletService.expireDueLots(walletId);
        assertThat(walletService.mainWallet(phone).getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void redeem_burns_lots_fifo_soonest_to_expire_first() {
        earn(50, "earn-3a");
        earn(50, "earn-3b");
        UUID walletId = walletService.mainWallet(phone).getId();

        List<PointLot> lots = pointLots.findByWalletId(walletId);
        assertThat(lots).hasSize(2);
        // Give them clearly distinct (still-future) expiries so FIFO is unambiguous.
        lots.sort((a, b) -> a.getEarnedAt().compareTo(b.getEarnedAt()));
        PointLot soonest = lots.get(0);
        PointLot latest = lots.get(1);
        soonest.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        latest.setExpiresAt(Instant.now().plus(10, ChronoUnit.DAYS));
        pointLots.save(soonest);
        pointLots.save(latest);

        // Redeem 60 -> all of the soonest lot (50) + 10 of the later one.
        redeem(60, "redeem-3");

        assertThat(pointLots.findById(soonest.getId()).orElseThrow().getRemainingAmount())
                .as("soonest-to-expire lot fully consumed first").isEqualByComparingTo("0");
        assertThat(pointLots.findById(latest.getId()).orElseThrow().getRemainingAmount())
                .as("later lot consumed only for the remainder").isEqualByComparingTo("40");
        assertThat(walletService.mainWallet(phone).getBalance()).isEqualByComparingTo("40");
    }

    private void earn(int amount, String ref) {
        transactionService.post(tenantId, merchantId,
                new Dtos.TransactionRequest(null, userId, null, TransactionType.PURCHASE,
                        new BigDecimal(amount), "USD", ref));
    }

    private void redeem(int amount, String ref) {
        redemptionService.redeemPoints(tenantId, merchantId,
                new Dtos.RedemptionRequest(null, userId, new BigDecimal(amount), "redeem", ref));
    }

    private void backdate(UUID walletId, Instant expiresAt) {
        for (PointLot lot : pointLots.findByWalletId(walletId)) {
            lot.setExpiresAt(expiresAt);
            pointLots.save(lot);
        }
    }
}
