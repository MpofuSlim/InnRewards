package com.innbucks.loyaltyservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.integration.GuestCheckoutNotifier;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.MerchantFixtures;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The till's split checkout end to end — real JWT, real services, real
 * Postgres: the customer pays part of the bill in cash and part with points in
 * one call to {@code POST /loyalty/shops/{shopId}/guest-checkout}. Pins that
 * both legs commit together (a refused leg rolls the other back), that a
 * resent reference moves nothing, and that both rows belong to the till's shop.
 */
class TillSplitCheckoutIT extends ControllerSecurityTestBase {

    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;
    @MockitoBean GuestCheckoutNotifier guestCheckoutNotifier;

    @Autowired MerchantService merchantService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired UserService userService;
    @Autowired ShopRepository shopRepository;
    @Autowired WalletRepository wallets;
    @Autowired LoyaltyTransactionRepository transactions;
    @Autowired ObjectMapper json;

    private UUID tenantId;
    private UUID merchantId;
    private UUID shopId;
    private String customerPhone;

    @BeforeEach
    void seed() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "it-fixture", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        tenantId = newTenant("till-split");
        merchantId = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest("Split Cafe " + UUID.randomUUID(), "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        ruleAdminService.createRule(tenantId, merchantId, new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        Shop shop = new Shop();
        shop.setTenantId(tenantId);
        shop.setMerchantId(merchantId);
        shop.setName("Split Cafe Avondale");
        shopId = shopRepository.save(shop).getId();

        // 1000 points (1 per $1), registered so they can be spent.
        customerPhone = "+26377" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
        transactionService.post(tenantId, merchantId,
                new Dtos.TransactionRequest(merchantId, null, customerPhone, TransactionType.PURCHASE,
                        new BigDecimal("1000"), "USD", "SEED-" + UUID.randomUUID()),
                EarnChannel.CHECKOUT_S2S);
        userService.promoteByPhone(customerPhone);
        SecurityContextHolder.clearContext();
        assertThat(balance()).isEqualByComparingTo("1000");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cashAndPoints_inOneCall_burnsAndEarns_bothAtThisShop() throws Exception {
        String ref = "POS-" + UUID.randomUUID();
        String body = checkout(customerPhone, "7.00", "5.00", ref)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pointsRedeemed").value(500))
                .andExpect(jsonPath("$.data.pointsEarned").value(7))
                .andExpect(jsonPath("$.data.walletBalanceAfter").value(507))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");
        assertThat(new BigDecimal(data.get("pointsValue").asText())).isEqualByComparingTo("5.00");

        LoyaltyTransaction burn = transactions.findById(UUID.fromString(data.get("redemptionTransactionId").asText()))
                .orElseThrow();
        LoyaltyTransaction earn = transactions.findById(UUID.fromString(data.get("purchaseTransactionId").asText()))
                .orElseThrow();
        assertThat(burn.getType()).isEqualTo(TransactionType.REDEMPTION);
        assertThat(burn.getReference()).isEqualTo(ref + ":redeem");
        assertThat(burn.getShopId()).isEqualTo(shopId);
        assertThat(earn.getReference()).isEqualTo(ref);
        assertThat(earn.getShopId()).isEqualTo(shopId);
        assertThat(balance()).isEqualByComparingTo("507");
    }

    @Test
    void aResentCheckout_replaysTheOriginal_andMovesNothing() throws Exception {
        String ref = "POS-" + UUID.randomUUID();
        checkout(customerPhone, "7.00", "5.00", ref).andExpect(status().isCreated());

        checkout(customerPhone, "7.00", "5.00", ref)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.pointsRedeemed").value(500))
                .andExpect(jsonPath("$.data.pointsEarned").value(7))
                .andExpect(jsonPath("$.data.walletBalanceAfter").value(507));
        assertThat(balance()).isEqualByComparingTo("507");
    }

    @Test
    void notEnoughPoints_refusesTheWholeCheckout_includingTheCashEarn() throws Exception {
        String ref = "POS-" + UUID.randomUUID();
        checkout(customerPhone, "7.00", "50.00", ref)   // $50 = 5000 points; they hold 1000
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(transactions.findFirstByMerchantIdAndReference(merchantId, ref)).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000");
    }

    @Test
    void aRefusedCashEarn_rollsTheBurnBack() throws Exception {
        // The burn runs first and succeeds; the earn is then refused because the
        // phone is a staff member's. Nothing may survive.
        when(userServiceClient.merchantStaffPhones(merchantId)).thenReturn(Optional.of(Set.of(customerPhone)));
        String ref = "POS-" + UUID.randomUUID();
        checkout(customerPhone, "7.00", "5.00", ref)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STAFF_RECIPIENT"));

        assertThat(transactions.findFirstByMerchantIdAndReference(merchantId, ref + ":redeem")).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1000");
    }

    @Test
    void cashOnly_stillWorksForAPhoneWithNoAccount() throws Exception {
        String newcomer = "+26378" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
        mockMvc.perform(post("/loyalty/shops/{shopId}/guest-checkout", shopId)
                        .header("Authorization", bearer(cashier()))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"%s\",\"cashAmount\":10.00}".formatted(newcomer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pointsEarned").value(10))
                .andExpect(jsonPath("$.data.pointsRedeemed").value(0))
                .andExpect(jsonPath("$.data.redemptionTransactionId").doesNotExist());
    }

    private ResultActions checkout(String phone, String cash, String pointsValue, String ref) throws Exception {
        return mockMvc.perform(post("/loyalty/shops/{shopId}/guest-checkout", shopId)
                .header("Authorization", bearer(cashier()))
                .header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"phoneNumber":"%s","cashAmount":%s,"pointsValue":%s,"reference":"%s"}
                        """.formatted(phone, cash, pointsValue, ref)));
    }

    private String cashier() {
        return TestJwtFactory.builder("cashier-" + UUID.randomUUID() + "@test.local")
                .role("SHOP_USER").merchantId(merchantId).shopId(shopId)
                .phoneNumber("+263779990001").sign(jwtSecret);
    }

    private BigDecimal balance() {
        return wallets.findByPhoneNumber(customerPhone).stream()
                .filter(w -> w.getType() == com.innbucks.loyaltyservice.entity.Wallet.Type.MAIN)
                .map(com.innbucks.loyaltyservice.entity.Wallet::getBalance)
                .findFirst().orElse(BigDecimal.ZERO);
    }
}
