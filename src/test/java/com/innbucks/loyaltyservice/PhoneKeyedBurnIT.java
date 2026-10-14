package com.innbucks.loyaltyservice;

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
import com.innbucks.loyaltyservice.service.WalletService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The phone-keyed burn and the user-bound replay, end to end — real JWTs, real
 * filters, real services, real Postgres, and no {@code tenant_members} row for
 * the till (it is a member through the merchant on its token):
 *
 * <ul>
 *   <li>a cashier burns a customer's points by PHONE — the only identifier a
 *       till holds — and the burn lands on its shop;</li>
 *   <li>an unknown phone is a 404 and creates no account;</li>
 *   <li>resending a REDEMPTION reference for a different customer is a 409 that
 *       moves and reveals nobody's balance, while the same customer's resend
 *       still replays.</li>
 * </ul>
 */
class PhoneKeyedBurnIT extends ControllerSecurityTestBase {

    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;

    @Autowired MerchantService merchantService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired UserService userService;
    @Autowired WalletService walletService;
    @Autowired LoyaltyUserRepository loyaltyUsers;
    @Autowired LoyaltyTransactionRepository transactions;
    @Autowired ObjectMapper json;

    private UUID tenantId;
    private UUID merchantId;
    private final UUID tillShop = UUID.randomUUID();
    private String alicePhone;
    private UUID aliceId;
    private String bobPhone;
    private UUID bobId;

    @BeforeEach
    void seed() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "it-fixture", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        tenantId = newTenant("phone-burn");
        merchantId = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest("Phone Burn Cafe " + UUID.randomUUID(), "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        ruleAdminService.createRule(tenantId, merchantId, new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        alicePhone = uniquePhone();
        aliceId = seedCustomer(alicePhone);
        bobPhone = uniquePhone();
        bobId = seedCustomer(bobPhone);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** A spendable customer holding 1000 points. Wallets are global per phone,
     *  so every test gets fresh numbers. */
    private UUID seedCustomer(String phone) {
        transactionService.post(tenantId, merchantId,
                new Dtos.TransactionRequest(merchantId, null, phone, TransactionType.PURCHASE,
                        new BigDecimal("1000"), "USD", "SEED-" + UUID.randomUUID()),
                EarnChannel.CHECKOUT_S2S);
        userService.promoteByPhone(phone);
        LoyaltyUser u = loyaltyUsers.findByTenantIdAndPhoneNumber(tenantId, phone).orElseThrow();
        assertThat(u.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
        assertThat(balance(phone)).isEqualByComparingTo("1000");
        return u.getId();
    }

    private static String uniquePhone() {
        return "+26377" + String.format("%07d", Math.floorMod(System.nanoTime() ^ UUID.randomUUID().hashCode(),
                10_000_000L));
    }

    /** The national spelling a cashier types: +263771234567 → 0771234567. */
    private static String national(String e164) {
        return "0" + e164.substring(4);
    }

    private BigDecimal balance(String phone) {
        return walletService.mainWallet(phone).getBalance();
    }

    private String cashier() {
        return TestJwtFactory.builder("cashier-" + UUID.randomUUID() + "@test.local")
                .role("SHOP_USER").merchantId(merchantId).shopId(tillShop)
                .phoneNumber("+263779990011").sign(jwtSecret);
    }

    private String customer(String phone) {
        return TestJwtFactory.builder("customer-" + UUID.randomUUID() + "@test.local")
                .role("CUSTOMER").tier(1).verified(true).phoneNumber(phone).sign(jwtSecret);
    }

    private ResultActions redeem(String token, String jsonBody) throws Exception {
        return mockMvc.perform(post("/loyalty/redeem")
                .header("Authorization", bearer(token))
                .header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody));
    }

    // merchantId is in every body: a CUSTOMER token carries no merchant, and on a
    // till token the claim wins over it anyway.
    private String byPhone(String phone, String reference) {
        return reference == null
                ? """
                  {"merchantId":"%s","assigneePhone":"%s","points":100,"reason":"till burn"}
                  """.formatted(merchantId, phone)
                : """
                  {"merchantId":"%s","assigneePhone":"%s","points":100,"reason":"till burn","reference":"%s"}
                  """.formatted(merchantId, phone, reference);
    }

    private String byUserId(UUID userId, String reference) {
        return """
               {"merchantId":"%s","userId":"%s","points":100,"reason":"till burn","reference":"%s"}
               """.formatted(merchantId, userId, reference);
    }

    private UUID transactionId(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).at("/data/transactionId").asText());
    }

    private BigDecimal newBalance(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return json.readTree(body).at("/data/newBalance").decimalValue();
    }

    // ---- the phone-keyed burn ----

    @Test
    void aCashier_burnsByThePhoneItTyped_andTheBurnIsInItsShopsFeed() throws Exception {
        String till = cashier();
        ResultActions result = redeem(till, byPhone(national(alicePhone), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"));
        UUID txnId = transactionId(result);
        assertThat(newBalance(result)).isEqualByComparingTo("900");

        assertThat(balance(alicePhone)).isEqualByComparingTo("900");
        assertThat(balance(bobPhone)).isEqualByComparingTo("1000");
        LoyaltyTransaction row = transactions.findById(txnId).orElseThrow();
        assertThat(row.getType()).isEqualTo(TransactionType.REDEMPTION);
        assertThat(row.getUserId()).isEqualTo(aliceId);
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
    void anUnknownPhone_is404_andCreatesNoAccount() throws Exception {
        String stranger = uniquePhone();

        redeem(cashier(), byPhone(stranger, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404 NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("user not found"));

        assertThat(loyaltyUsers.findByTenantIdAndPhoneNumber(tenantId, stranger)).isEmpty();
        assertThat(loyaltyUsers.findAll()).noneMatch(u -> stranger.equals(u.getPhoneNumber()));
    }

    @Test
    void theRequestShapeIsRefusedBeforeAnythingMoves() throws Exception {
        redeem(cashier(), """
                {"merchantId":"%s","userId":"%s","assigneePhone":"%s","points":100}
                """.formatted(merchantId, aliceId, alicePhone))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECIPIENT_REQUIRED"))
                .andExpect(jsonPath("$.message").value("supply exactly one of userId or assigneePhone"));
        redeem(cashier(), """
                {"merchantId":"%s","assigneePhone":"  ","points":100}
                """.formatted(merchantId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECIPIENT_REQUIRED"));
        redeem(cashier(), byPhone("0771-abc", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PHONE"))
                .andExpect(jsonPath("$.message").value("Invalid phone number: 0771-abc"));

        assertThat(balance(alicePhone)).isEqualByComparingTo("1000");
    }

    @Test
    void aCustomer_burnsByTheirOwnPhone_andNoOtherWhetherEnrolledOrNot() throws Exception {
        redeem(customer(alicePhone), byPhone(bobPhone, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_WALLET_OWNER"));
        redeem(customer(alicePhone), byPhone(uniquePhone(), null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_WALLET_OWNER"));
        assertThat(balance(bobPhone)).isEqualByComparingTo("1000");

        redeem(customer(alicePhone), byPhone(alicePhone, null))
                .andExpect(status().isOk());
        assertThat(balance(alicePhone)).isEqualByComparingTo("900");
    }

    // ---- the replay is bound to the customer it debited ----

    @Test
    void theSameCustomersResend_replays_byPhoneOrByUserId() throws Exception {
        String reference = "POS-" + UUID.randomUUID();
        UUID first = transactionId(redeem(cashier(), byPhone(national(alicePhone), reference))
                .andExpect(status().isOk()));

        ResultActions replay = redeem(cashier(), byPhone(alicePhone, reference)).andExpect(status().isOk());
        UUID byPhoneAgain = transactionId(replay);
        assertThat(newBalance(replay)).isEqualByComparingTo("900");
        UUID byIdAgain = transactionId(redeem(cashier(), byUserId(aliceId, reference))
                .andExpect(status().isOk()));

        assertThat(byPhoneAgain).isEqualTo(first);
        assertThat(byIdAgain).isEqualTo(first);
        assertThat(balance(alicePhone)).as("debited once").isEqualByComparingTo("900");
    }

    @Test
    void anotherCustomersReference_is409_andMovesOrRevealsNobody() throws Exception {
        String reference = "POS-" + UUID.randomUUID();
        redeem(cashier(), byPhone(alicePhone, reference)).andExpect(status().isOk());

        // Before the fix each of these was a 200 carrying BOB's balance, no debit.
        for (String body : List.of(byPhone(bobPhone, reference), byUserId(bobId, reference))) {
            redeem(cashier(), body)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_REFERENCE"))
                    .andExpect(jsonPath("$.message").value("transaction with this merchant reference already exists"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        assertThat(balance(alicePhone)).isEqualByComparingTo("900");
        assertThat(balance(bobPhone)).isEqualByComparingTo("1000");
        assertThat(transactions.findFirstByMerchantIdAndReference(merchantId, reference))
                .get().extracting(LoyaltyTransaction::getUserId).isEqualTo(aliceId);
    }
}
