package com.innbucks.loyaltyservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A merchant's POS till end to end — real JWTs, real filters, real services,
 * real Postgres, and NO {@code tenant_members} row for any till account:
 *
 * <ul>
 *   <li>C1: a cashier (SHOP_USER) burns a customer's points at its own
 *       merchant, and the burn lands in its shop's {@code /my-shop} feed;</li>
 *   <li>C2: shop staff reach the tenant through the merchant in their token;</li>
 *   <li>C3: a till's QR earn carries the issuing shop, and the till can see
 *       its QR was scanned through {@code POST /loyalty/qr/status}.</li>
 * </ul>
 *
 * The old controller test for the cashier burn mocked the service, which is how
 * every real cashier burn shipped as a 403.
 */
class TillCashierFlowsIT extends ControllerSecurityTestBase {

    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;

    @Autowired MerchantService merchantService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired UserService userService;
    @Autowired LoyaltyUserRepository loyaltyUsers;
    @Autowired LoyaltyTransactionRepository transactions;
    @Autowired ObjectMapper json;

    private UUID tenantId;
    private UUID merchantId;
    private UUID otherMerchantId;
    private final UUID tillShop = UUID.randomUUID();
    private String customerPhone;
    private UUID customerId;

    @BeforeEach
    void seed() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "it-fixture", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        tenantId = newTenant("till");
        merchantId = merchant("Till Cafe ");
        otherMerchantId = merchant("Next Door ");

        customerPhone = "+26377" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
        transactionService.post(tenantId, merchantId,
                new Dtos.TransactionRequest(merchantId, null, customerPhone, TransactionType.PURCHASE,
                        new BigDecimal("1000"), "USD", "SEED-" + UUID.randomUUID()),
                EarnChannel.CHECKOUT_S2S);
        userService.promoteByPhone(customerPhone);
        LoyaltyUser u = loyaltyUsers.findByTenantIdAndPhoneNumber(tenantId, customerPhone).orElseThrow();
        assertThat(u.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
        customerId = u.getId();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UUID merchant(String name) {
        UUID id = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest(name + UUID.randomUUID(), "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        ruleAdminService.createRule(tenantId, id, new Dtos.RuleRequest(null, TransactionType.QR_PAY,
                BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        ruleAdminService.createRule(tenantId, id, new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        return id;
    }

    private String cashier(UUID merchant, UUID shop) {
        return TestJwtFactory.builder("cashier-" + UUID.randomUUID() + "@test.local")
                .role("SHOP_USER").merchantId(merchant).shopId(shop)
                .phoneNumber("+263779990001").sign(jwtSecret);
    }

    private String shopAdmin(UUID merchant, UUID shop) {
        return TestJwtFactory.builder("manager-" + UUID.randomUUID() + "@test.local")
                .role("SHOP_ADMIN").merchantId(merchant).shopId(shop)
                .phoneNumber("+263779990002").sign(jwtSecret);
    }

    private String customer(String phone) {
        return TestJwtFactory.builder("customer-" + UUID.randomUUID() + "@test.local")
                .role("CUSTOMER").tier(1).verified(true).phoneNumber(phone).sign(jwtSecret);
    }

    private org.springframework.test.web.servlet.ResultActions redeem(String token, UUID bodyMerchant,
                                                                       UUID userId) throws Exception {
        return mockMvc.perform(post("/loyalty/redeem")
                .header("Authorization", bearer(token))
                .header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchantId":"%s","userId":"%s","points":100,"reason":"till burn"}
                        """.formatted(bodyMerchant, userId)));
    }

    // ---- C1 + C2: the cashier burn ----

    @Test
    void aCashier_burnsACustomersPoints_andTheBurnIsInItsShopsFeed() throws Exception {
        String till = cashier(merchantId, tillShop);
        String body = redeem(till, merchantId, customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andReturn().getResponse().getContentAsString();
        UUID txnId = UUID.fromString(json.readTree(body).at("/data/transactionId").asText());

        LoyaltyTransaction row = transactions.findById(txnId).orElseThrow();
        assertThat(row.getType()).isEqualTo(TransactionType.REDEMPTION);
        assertThat(row.getMerchantId()).isEqualTo(merchantId);
        assertThat(row.getShopId()).isEqualTo(tillShop);

        mockMvc.perform(get("/loyalty/transactions/my-shop")
                        .header("Authorization", bearer(till))
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.id == '%s')].type".formatted(txnId))
                        .value("REDEMPTION"));
    }

    @Test
    void aCashierNamingAnotherMerchant_stillBurnsAtItsOwn() throws Exception {
        // The token's merchant claim wins; the body cannot move the burn.
        String body = redeem(cashier(merchantId, tillShop), otherMerchantId, customerId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID txnId = UUID.fromString(json.readTree(body).at("/data/transactionId").asText());
        assertThat(transactions.findById(txnId).orElseThrow().getMerchantId()).isEqualTo(merchantId);
    }

    @Test
    void aCashierOfAMerchantInAnotherTenant_isNotAMemberHere() throws Exception {
        UUID foreignTenant = newTenant("till-foreign");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "it-fixture", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        UUID foreignMerchant = MerchantFixtures.createAsPlatform(merchantService, foreignTenant,
                new Dtos.MerchantRequest("Far Away " + UUID.randomUUID(), "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        SecurityContextHolder.clearContext();

        redeem(cashier(foreignMerchant, UUID.randomUUID()), foreignMerchant, customerId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403 FORBIDDEN"));
    }

    @Test
    void aCustomer_stillBurnsOnlyTheirOwnWallet() throws Exception {
        redeem(customer("+263779990077"), merchantId, customerId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_WALLET_OWNER"));
        redeem(customer(customerPhone), merchantId, customerId)
                .andExpect(status().isOk());
    }

    // ---- C3: the till's QR ----

    @Test
    void aTillsQr_earnsForItsShop_andTheTillSeesItScanned() throws Exception {
        String manager = shopAdmin(merchantId, tillShop);
        String issued = mockMvc.perform(post("/loyalty/qr/issue")
                        .header("Authorization", bearer(manager))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceType":"MERCHANT","sourceId":"%s","transactionType":"QR_PAY",
                                 "amount":40.00,"currency":"USD"}
                                """.formatted(merchantId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode qr = json.readTree(issued).get("data");
        String token = qr.get("token").asText();

        // Any cashier of the merchant may poll; the QR is not yet scanned.
        String till = cashier(merchantId, UUID.randomUUID());
        qrStatus(till, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.transactionId").doesNotExist());

        String consumed = mockMvc.perform(post("/loyalty/qr/consume")
                        .header("Authorization", bearer(customer(customerPhone)))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","signature":"%s","userId":"%s","reference":"POS-%s"}
                                """.formatted(token, qr.get("signature").asText(), customerId, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").value(tillShop.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID earnId = UUID.fromString(json.readTree(consumed).at("/data/id").asText());
        assertThat(transactions.findById(earnId).orElseThrow().getShopId()).isEqualTo(tillShop);

        qrStatus(till, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONSUMED"))
                .andExpect(jsonPath("$.data.transactionId").value(earnId.toString()))
                .andExpect(jsonPath("$.data.consumedAt").exists());

        // Another merchant's cashier (same tenant) and the customer: the unknown-token 404.
        qrStatus(cashier(otherMerchantId, UUID.randomUUID()), token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("This QR code is invalid or has expired."));
        qrStatus(customer(customerPhone), token)
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions qrStatus(String bearerToken, String qrToken)
            throws Exception {
        return mockMvc.perform(post("/loyalty/qr/status")
                .header("Authorization", bearer(bearerToken))
                .header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(qrToken)));
    }
}
