package com.innbucks.loyaltyservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.integration.GuestCheckoutNotifier;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A POS integration end to end — real JWTs (a POS API key's till token as
 * user-service mints it), real services, real Postgres:
 * <ul>
 *   <li>the balance lookup a till calls before spending points, and that its
 *       "use all" value burns the whole MAIN wallet and nothing more;</li>
 *   <li>a POS key refunding its own sale, and the limits on that.</li>
 * </ul>
 */
class TillBalanceAndPosReversalIT extends ControllerSecurityTestBase {

    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;
    @MockitoBean GuestCheckoutNotifier guestCheckoutNotifier;

    @Autowired MerchantService merchantService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired UserService userService;
    @Autowired ShopRepository shopRepository;
    @Autowired WalletRepository wallets;
    @Autowired LoyaltyUserRepository loyaltyUsers;
    @Autowired LoyaltyTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private UUID tenantId;
    private UUID merchantId;
    private UUID shopId;
    private UUID otherShopId;
    private UUID keyId;
    private String customerPhone;

    @BeforeEach
    void seed() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "it-fixture", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        tenantId = newTenant("pos-till");
        merchantId = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest("POS Cafe " + UUID.randomUUID(), "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        ruleAdminService.createRule(tenantId, merchantId, new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        shopId = newShop("POS Cafe Samora Machel");
        otherShopId = newShop("POS Cafe Avondale");
        keyId = UUID.randomUUID();

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

    // ------------------------------------------------------------ lookup

    @Test
    void lookup_showsTheBalance_andItsUseAllValue_burnsExactlyTheWallet() throws Exception {
        String body = lookup(posKey(keyId), shopId, customerPhone)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.points").value(1000))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.canSpendHere").value(true))
                .andExpect(jsonPath("$.data.phoneMasked").value("****" + customerPhone.substring(customerPhone.length() - 4)))
                .andReturn().getResponse().getContentAsString();
        String useAll = json.readTree(body).get("data").get("pointsValue").asText();
        assertThat(new BigDecimal(useAll)).isEqualByComparingTo("10.00");

        checkout(posKey(keyId), customerPhone, "0", useAll, "POS-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pointsRedeemed").value(1000));
        assertThat(balance()).isEqualByComparingTo("0");
    }

    @Test
    void lookup_ofAPhoneWithNoAccount_isNone_andCreatesNothing() throws Exception {
        String stranger = "+26378" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
        lookup(posKey(keyId), shopId, stranger)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points").value(0))
                .andExpect(jsonPath("$.data.accountStatus").value("NONE"))
                .andExpect(jsonPath("$.data.canSpendHere").value(false));
        assertThat(loyaltyUsers.findByTenantIdAndPhoneNumber(tenantId, stranger)).isEmpty();
        assertThat(wallets.findByPhoneNumber(stranger)).isEmpty();
    }

    @Test
    void lookup_atAnotherOutlet_isRefused() throws Exception {
        lookup(posKey(keyId), otherShopId, customerPhone)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SHOP_MEMBER"));
    }

    @Test
    void lookup_ofSomethingThatIsNotAPhone_isBadPhone() throws Exception {
        lookup(posKey(keyId), shopId, "12345")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PHONE"));
    }

    // ------------------------------------------------------------ reversals

    @Test
    void aPosKey_reversesItsOwnSale_bothTheBurnAndTheEarn() throws Exception {
        JsonNode sale = sale(posKey(keyId));
        assertThat(balance()).isEqualByComparingTo("507");   // 1000 − 500 + 7

        reverse(posKey(keyId), sale.get("redemptionTransactionId").asText()).andExpect(status().isOk());
        reverse(posKey(keyId), sale.get("purchaseTransactionId").asText()).andExpect(status().isOk());
        assertThat(balance()).isEqualByComparingTo("1000");
    }

    @Test
    void aPosKey_cannotReverseAnotherKeysSale() throws Exception {
        JsonNode sale = sale(posKey(keyId));
        reverse(posKey(UUID.randomUUID()), sale.get("purchaseTransactionId").asText())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_PERMITTED"));
        assertThat(balance()).isEqualByComparingTo("507");
    }

    @Test
    void aPosKey_cannotReverseASaleOlderThanTheWindow() throws Exception {
        JsonNode sale = sale(posKey(keyId));
        UUID earn = UUID.fromString(sale.get("purchaseTransactionId").asText());
        jdbc.update("UPDATE loyalty_transactions SET created_at = now() - interval '31 days' WHERE id = ?", earn);

        reverse(posKey(keyId), earn.toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REVERSAL_WINDOW_PASSED"));
        assertThat(transactions.findById(earn).orElseThrow().getStatus())
                .isEqualTo(LoyaltyTransaction.Status.POSTED);
    }

    @Test
    void aPosKey_cannotUndoARefund() throws Exception {
        JsonNode sale = sale(posKey(keyId));
        String reversal = json.readTree(reverse(posKey(keyId), sale.get("purchaseTransactionId").asText())
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("data").get("id").asText();

        reverse(posKey(keyId), reversal)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_PERMITTED"));
    }

    @Test
    void aCashierSignedInAsAPerson_stillCannotReverse_evenTheirOwnSale() throws Exception {
        String cashier = TestJwtFactory.builder("cashier-" + UUID.randomUUID() + "@test.local")
                .role("SHOP_USER").merchantId(merchantId).shopId(shopId).userId(UUID.randomUUID())
                .sign(jwtSecret);
        JsonNode sale = sale(cashier);
        reverse(cashier, sale.get("purchaseTransactionId").asText())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_PERMITTED"));
    }

    @Test
    void aTokenThatOnlyLooksLikeAKey_isTreatedAsAPerson() throws Exception {
        // The subject says pos-key:<id>, but the userUuid claim names a
        // different id: not a key's token, so no key powers.
        String forged = TestJwtFactory.builder("pos-key:" + keyId)
                .role("SHOP_USER").merchantId(merchantId).shopId(shopId).userId(UUID.randomUUID())
                .sign(jwtSecret);
        JsonNode sale = sale(forged);
        reverse(forged, sale.get("purchaseTransactionId").asText())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_PERMITTED"));
    }

    @Test
    void aSupervisor_stillReversesAnySaleOfTheMerchant() throws Exception {
        JsonNode sale = sale(posKey(keyId));
        String supervisor = TestJwtFactory.shopAdmin("sup-" + UUID.randomUUID() + "@test.local",
                merchantId, shopId, jwtSecret);
        reverse(supervisor, sale.get("purchaseTransactionId").asText()).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ helpers

    private UUID newShop(String name) {
        Shop shop = new Shop();
        shop.setTenantId(tenantId);
        shop.setMerchantId(merchantId);
        shop.setName(name);
        return shopRepository.save(shop).getId();
    }

    private String posKey(UUID id) {
        return TestJwtFactory.posKey(id, merchantId, shopId, jwtSecret);
    }

    /** A $12 sale: $7 cash, $5 in points. Returns the checkout's data node. */
    private JsonNode sale(String token) throws Exception {
        String body = checkout(token, customerPhone, "7.00", "5.00", "POS-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private ResultActions lookup(String token, UUID shop, String phone) throws Exception {
        return mockMvc.perform(post("/loyalty/shops/{shopId}/balance-lookup", shop)
                .header("Authorization", bearer(token))
                .header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"%s\"}".formatted(phone)));
    }

    private ResultActions checkout(String token, String phone, String cash, String pointsValue, String ref)
            throws Exception {
        return mockMvc.perform(post("/loyalty/shops/{shopId}/guest-checkout", shopId)
                .header("Authorization", bearer(token))
                .header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"phoneNumber":"%s","cashAmount":%s,"pointsValue":%s,"reference":"%s"}
                        """.formatted(phone, cash, pointsValue, ref)));
    }

    private ResultActions reverse(String token, String transactionId) throws Exception {
        return mockMvc.perform(post("/loyalty/transactions/{id}/reverse", transactionId)
                .header("Authorization", bearer(token))
                .header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Customer returned the meal\"}"));
    }

    private BigDecimal balance() {
        return wallets.findFirstByPhoneNumberAndType(customerPhone, Wallet.Type.MAIN)
                .map(Wallet::getBalance).orElse(BigDecimal.ZERO);
    }
}
