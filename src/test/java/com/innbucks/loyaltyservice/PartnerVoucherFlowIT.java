package com.innbucks.loyaltyservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.PartnerJtiStore;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.MerchantFixtures;
import com.innbucks.loyaltyservice.testsupport.PartnerJws;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The partner voucher API (V62) end to end, the way the InnBucks middleware will
 * call it for MWOS: a SUPER_ADMIN registers the partner, its key and the merchant
 * link through the admin endpoints, then every call is a real ES256 JWS through
 * the real filter, controller, services and Postgres, with SIMULATED funding.
 * Only the jti store (Redis, absent in tests) and the outbound messages are
 * stand-ins.
 */
@TestPropertySource(properties = {
        "loyalty.voucher-partner.enabled=true",
        "loyalty.voucher-partner.audience=" + PartnerVoucherFlowIT.AUDIENCE,
        "loyalty.voucher-partner.funding=SIMULATED",
        "loyalty.voucher-partner.simulated-delay=PT0S",
        // The test drives every leg itself; the background job must not race it.
        "loyalty.voucher-partner.reconcile-interval=PT1H"
})
class PartnerVoucherFlowIT extends ControllerSecurityTestBase {

    static final String AUDIENCE = "innbucks-loyalty-vouchers:it";
    private static final String CALLER_IP = "203.0.113.10";

    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;
    /** Redis is absent in tests; this keeps the same one-use rule in memory. */
    @MockitoBean PartnerJtiStore jtiStore;

    @Autowired MerchantService merchantService;
    @Autowired MerchantRepository merchantRepository;
    @Autowired VoucherRepository voucherRepository;
    @Autowired VoucherRedemptionRepository redemptionRepository;
    @Autowired ObjectMapper json;

    private final KeyPair keys = PartnerJws.ecKeyPair();
    private UUID tenantId;
    private UUID merchantId;
    private UUID partnerId;
    private String issuer;
    private String kid;

    @BeforeEach
    void setUp() throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        when(jtiStore.firstUse(anyString(), any())).thenAnswer(inv -> seen.add(inv.getArgument(0)));

        tenantId = newTenant("mwos");
        merchantId = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest("MWOS " + UUID.randomUUID(), "Betting", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();

        issuer = "innbucks-middleware-" + UUID.randomUUID();
        kid = "mw-it-" + UUID.randomUUID();
        JsonNode created = admin(post("/loyalty/admin/voucher-partners"), Map.of(
                "name", "InnBucks middleware", "issuer", issuer, "allowedIps", List.of(CALLER_IP)), 201);
        partnerId = UUID.fromString(created.path("data").path("partnerId").asText());
        admin(post("/loyalty/admin/voucher-partners/" + partnerId + "/keys"), Map.of(
                "kid", kid, "alg", "ES256", "publicKeyPem", PartnerJws.pem(keys.getPublic())), 200);
        JsonNode linked = admin(post("/loyalty/admin/voucher-partners/" + partnerId + "/merchants"), Map.of(
                "merchantId", merchantId.toString(), "merchantCode", "MWOS"), 200);
        // By default the code works like cash: no phone has to match (owner decision, 2026-11-12).
        assertThat(linked.path("data").path("merchants").get(0).path("bindRedeemToHolder").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ flows

    @Test
    void aSingleUseVoucher_refusesPartOfItsValue_andIsSpentWholeInOneGo() throws Exception {
        String phone = newPhone();
        String reference = "MWOS-ONE-" + UUID.randomUUID();
        Map<String, Object> sale = cashSale(reference, phone, "20.00");
        sale.put("usage", "single");

        Call issued = partner("generate", sale);
        assertThat(issued.status()).isEqualTo(201);
        JsonNode voucherView = issued.body().path("data").path("voucher");
        assertThat(voucherView.path("usage").asText()).isEqualTo("SINGLE");
        String code = voucherView.path("code").asText();
        assertThat(voucherRepository.findById(UUID.fromString(voucherView.path("voucherId").asText()))
                .orElseThrow().isSingleRedemption()).isTrue();

        // The same reference with usage MULTIPLE is a different request.
        assertThat(partner("generate", cashSale(reference, phone, "20.00")).body().path("code").asText())
                .isEqualTo("IDEMPOTENCY_MISMATCH");

        // Part of it is refused, and nothing is spent.
        Call part = partner("redeem", redeem(code, "15.00", "BET-ONE-1", phone));
        assertThat(part.status()).isEqualTo(400);
        assertThat(part.body().path("code").asText()).isEqualTo("PARTIAL_USE_NOT_ALLOWED");
        Call balance = partner("inquire", Map.of("merchant", "MWOS", "by", "CODE", "code", code,
                "endUserRef", "MWOS-ACC-1", "endUserPhone", phone));
        assertThat(balance.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(balance.body().path("data").path("usage").asText()).isEqualTo("SINGLE");

        // The whole value in one go is accepted.
        Call whole = partner("redeem", redeem(code, "20.00", "BET-ONE-2", phone));
        assertThat(whole.status()).isEqualTo(200);
        assertThat(whole.body().path("data").path("status").asText()).isEqualTo("REDEEMED");
        assertThat(whole.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("0");

        // Without a usage the voucher is SINGLE.
        Map<String, Object> plain = cashSale("MWOS-ONE-" + UUID.randomUUID(), newPhone(), "19.00");
        plain.remove("usage");
        Call byDefault = partner("generate", plain);
        assertThat(byDefault.status()).isEqualTo(201);
        assertThat(byDefault.body().path("data").path("voucher").path("usage").asText()).isEqualTo("SINGLE");

        // A usage that is neither SINGLE nor MULTIPLE is a field error.
        Map<String, Object> bad = cashSale("MWOS-ONE-" + UUID.randomUUID(), newPhone(), "18.00");
        bad.put("usage", "TWICE");
        Call refused = partner("generate", bad);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().path("data").path("usage").asText()).isEqualTo("must be SINGLE or MULTIPLE");
    }

    @Test
    void aRedeemAboveTheBalance_isAlwaysRefused_forAPartner() throws Exception {
        String phone = newPhone();
        Call issued = partner("generate", cashSale("MWOS-OVER-" + UUID.randomUUID(), phone, "20.00"));
        assertThat(issued.status()).isEqualTo(201);
        String code = issued.body().path("data").path("voucher").path("code").asText();

        // More than the balance: refused, nothing spent, the balance named.
        Call over = partner("redeem", redeem(code, "25.00", "BET-OVER-1", phone));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().path("code").asText()).isEqualTo("INSUFFICIENT_VOUCHER_BALANCE");
        assertThat(over.body().path("message").asText()).isEqualTo("This voucher has only USD 20.00 left.");
        assertThat(over.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(over.body().path("data").path("currency").asText()).isEqualTo("USD");
        Call balance = partner("inquire", Map.of("merchant", "MWOS", "by", "CODE", "code", code,
                "endUserRef", "MWOS-ACC-1", "endUserPhone", phone));
        assertThat(balance.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("20.00");

        // Spend part, then ask for more than is left: refused again, naming the new balance.
        assertThat(partner("redeem", redeem(code, "15.00", "BET-OVER-2", phone)).status()).isEqualTo(200);
        Call again = partner("redeem", redeem(code, "6.00", "BET-OVER-3", phone));
        assertThat(again.body().path("code").asText()).isEqualTo("INSUFFICIENT_VOUCHER_BALANCE");
        assertThat(again.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("5.00");

        // Exactly the balance is accepted.
        Call rest = partner("redeem", redeem(code, "5.00", "BET-OVER-4", phone));
        assertThat(rest.status()).isEqualTo(200);
        assertThat(rest.body().path("data").path("status").asText()).isEqualTo("REDEEMED");
    }

    @Test
    void validityDays_setsTheVouchersExpiry_isBounded_andIsPartOfTheRequest() throws Exception {
        String phone = newPhone();
        String reference = "MWOS-VAL-" + UUID.randomUUID();
        Map<String, Object> sale = cashSale(reference, phone, "15.00");
        sale.put("validityDays", 30);

        Call issued = partner("generate", sale);
        assertThat(issued.status()).isEqualTo(201);
        Voucher voucher = voucherRepository.findById(UUID.fromString(
                issued.body().path("data").path("voucher").path("voucherId").asText())).orElseThrow();
        assertThat(java.time.Duration.between(voucher.getIssuedAt(), voucher.getExpiresAt()))
                .isEqualTo(java.time.Duration.ofDays(30));
        assertThat(issued.body().path("data").path("voucher").path("expiresAt").asText())
                .isEqualTo(voucher.getExpiresAt().toString());

        // The same reference asking for another validity is a different request.
        Map<String, Object> changed = cashSale(reference, phone, "15.00");
        changed.put("validityDays", 60);
        assertThat(partner("generate", changed).body().path("code").asText()).isEqualTo("IDEMPOTENCY_MISMATCH");

        // Without it, the merchant's own validity applies (the 365-day default here).
        Call standard = partner("generate", cashSale("MWOS-VAL-" + UUID.randomUUID(), newPhone(), "16.00"));
        Voucher plain = voucherRepository.findById(UUID.fromString(
                standard.body().path("data").path("voucher").path("voucherId").asText())).orElseThrow();
        // The rule path takes its own Instant.now() just after issuedAt, so it is
        // a few microseconds short of 365 days; only the partner's own validity
        // is counted from issuedAt exactly.
        assertThat(java.time.Duration.between(plain.getIssuedAt(), plain.getExpiresAt()))
                .isCloseTo(java.time.Duration.ofDays(365), java.time.Duration.ofSeconds(1));

        for (int bad : new int[]{0, -5, 366}) {
            Map<String, Object> outOfRange = cashSale("MWOS-VAL-" + UUID.randomUUID(), newPhone(), "17.00");
            outOfRange.put("validityDays", bad);
            Call refused = partner("generate", outOfRange);
            assertThat(refused.status()).as("validityDays " + bad).isEqualTo(400);
            assertThat(refused.body().path("data").path("validityDays").asText())
                    .isEqualTo("must be between 1 and 365 days");
        }
    }

    @Test
    void aPosCashSale_issuesAVoucher_whoseCodeTheTillPrints_andItRedeemsInSettledSlices() throws Exception {
        String phone = newPhone();
        String reference = "MWOS-POS-" + UUID.randomUUID();
        Map<String, Object> sale = cashSale(reference, phone, "20.00");

        Call issued = partner("generate", sale);
        assertThat(issued.status()).isEqualTo(201);
        JsonNode data = issued.body().path("data");
        assertThat(data.path("status").asText()).isEqualTo("ISSUED");
        assertThat(data.path("replayed").asBoolean()).isFalse();
        assertThat(data.path("funding").path("method").asText()).isEqualTo("CASH");
        assertThat(data.path("funding").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(data.path("funding").path("simulated").asBoolean()).isTrue();
        assertThat(data.path("voucher").path("holderPhone").asText()).doesNotContain(phone.substring(4));
        assertThat(data.path("voucher").path("remainingBalance").decimalValue()).isEqualByComparingTo("20.00");

        UUID voucherId = UUID.fromString(data.path("voucher").path("voucherId").asText());
        Voucher voucher = voucherRepository.findById(voucherId).orElseThrow();
        assertThat(voucher.getPartnerId()).isEqualTo(partnerId);
        assertThat(voucher.getAssigneePhone()).isEqualTo(phone);
        assertThat(merchantRepository.findById(merchantId).orElseThrow().isEscrowMode()).isTrue();
        String code = voucher.getCode();
        // A POS sale carries the code for the till to print on the customer's slip.
        assertThat(data.path("voucher").path("code").asText()).isEqualTo(code).matches("\\d{16}");

        // The same request again is the same order; the same reference for another sale is refused.
        Call replay = partner("generate", sale);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("data").path("replayed").asBoolean()).isTrue();
        assertThat(replay.body().path("data").path("orderRef").asText()).isEqualTo(data.path("orderRef").asText());
        assertThat(replay.body().path("data").path("voucher").path("code").asText()).isEqualTo(code);
        Call mismatch = partner("generate", cashSale(reference, phone, "21.00"));
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.code()).isEqualTo("IDEMPOTENCY_MISMATCH");

        Call byReference = partner("inquire", Map.of("merchant", "MWOS", "by", "PARTNER_REFERENCE",
                "partnerReference", reference));
        assertThat(byReference.status()).isEqualTo(200);
        assertThat(byReference.body().path("data").path("status").asText()).isEqualTo("ISSUED");
        // ...and so does the inquiry, so a slip that failed to print can be printed again.
        assertThat(byReference.body().path("data").path("voucher").path("code").asText()).isEqualTo(code);

        Call byCode = partner("inquire", Map.of("merchant", "MWOS", "by", "CODE", "code", code,
                "endUserRef", "MWOS-ACC-1", "endUserPhone", phone));
        assertThat(byCode.status()).isEqualTo(200);
        assertThat(byCode.body().path("data").path("redeemable").asBoolean()).isTrue();
        assertThat(byCode.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("20.00");

        // The code works like cash: another phone, or none, reads it too.
        Call anotherPhone = partner("inquire", Map.of("merchant", "MWOS", "by", "CODE", "code", code,
                "endUserRef", "MWOS-ACC-2", "endUserPhone", newPhone()));
        assertThat(anotherPhone.status()).isEqualTo(200);
        Call noPhone = partner("inquire", Map.of("merchant", "MWOS", "by", "CODE", "code", code,
                "endUserRef", "MWOS-ACC-2"));
        assertThat(noPhone.status()).isEqualTo(200);

        // A merchant that ties redemption to the holder refuses any other phone, and a missing one.
        admin(put("/loyalty/admin/voucher-partners/" + partnerId + "/merchants/MWOS"),
                Map.of("bindRedeemToHolder", true), 200);
        Call notTheHolder = partner("inquire", Map.of("merchant", "MWOS", "by", "CODE", "code", code,
                "endUserRef", "MWOS-ACC-2", "endUserPhone", newPhone()));
        assertThat(notTheHolder.status()).isEqualTo(403);
        assertThat(notTheHolder.code()).isEqualTo("NOT_VOUCHER_OWNER");
        Call phoneMissing = partner("inquire", Map.of("merchant", "MWOS", "by", "CODE", "code", code,
                "endUserRef", "MWOS-ACC-2"));
        assertThat(phoneMissing.status()).isEqualTo(400);
        admin(put("/loyalty/admin/voucher-partners/" + partnerId + "/merchants/MWOS"),
                Map.of("bindRedeemToHolder", false), 200);

        Call first = partner("redeem", redeem(code, "5.00", "BET-1", phone));
        assertThat(first.status()).isEqualTo(200);
        JsonNode used = first.body().path("data");
        assertThat(used.path("status").asText()).isEqualTo("PARTIALLY_USED");
        assertThat(used.path("value").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(used.path("remainingBalance").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(used.path("replayed").asBoolean()).isFalse();
        assertThat(used.path("settlement").path("status").asText()).isEqualTo("SETTLED");
        assertThat(used.path("settlement").path("transferReference").asText()).startsWith("SIM-SETTLE-");

        Call resent = partner("redeem", redeem(code, "5.00", "BET-1", phone));
        assertThat(resent.status()).isEqualTo(200);
        assertThat(resent.body().path("data").path("replayed").asBoolean()).isTrue();
        assertThat(resent.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("15.00");

        Call changed = partner("redeem", redeem(code, "6.00", "BET-1", phone));
        assertThat(changed.status()).isEqualTo(422);
        assertThat(changed.code()).isEqualTo("IDEMPOTENCY_MISMATCH");

        Call rest = partner("redeem", redeem(code, null, "BET-2", phone));
        assertThat(rest.status()).isEqualTo(200);
        assertThat(rest.body().path("data").path("status").asText()).isEqualTo("REDEEMED");
        assertThat(rest.body().path("data").path("value").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(rest.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("0.00");

        Call spent = partner("redeem", redeem(code, "1.00", "BET-3", phone));
        assertThat(spent.status()).isEqualTo(409);
        assertThat(spent.code()).isEqualTo("ALREADY_REDEEMED");

        Call settlement = partner("inquire", Map.of("merchant", "MWOS", "by", "REDEMPTION", "reference", "BET-1"));
        assertThat(settlement.status()).isEqualTo(200);
        assertThat(settlement.body().path("data").path("settlement").path("status").asText()).isEqualTo("SETTLED");

        List<VoucherRedemption> uses = redemptionRepository.findAll().stream()
                .filter(r -> voucherId.equals(r.getVoucherId())
                        && r.getResult() == VoucherRedemption.Result.SUCCESS)
                .toList();
        assertThat(uses).hasSize(2).allSatisfy(r -> {
            assertThat(r.getPartnerId()).isEqualTo(partnerId);
            assertThat(r.getEndUserRef()).isEqualTo("MWOS-ACC-1");
            assertThat(r.getSettlementStatus()).isEqualTo(VoucherRedemption.SettlementStatus.SETTLED);
        });
        assertThat(voucherRepository.findById(voucherId).orElseThrow().getStatus())
                .isEqualTo(Voucher.Status.REDEEMED);
    }

    @Test
    void anInnbucksCode_waitsForTheCustomerToPay_thenIssues_andAnOnlineSaleNeverCarriesTheCode() throws Exception {
        String phone = newPhone();
        String reference = "MWOS-WEB-" + UUID.randomUUID();
        Map<String, Object> sale = sale(reference, "ONLINE", phone, "10.00", Map.of("method", "INNBUCKS_CODE"));

        Call awaiting = partner("generate", sale);
        assertThat(awaiting.status()).isEqualTo(202);
        JsonNode data = awaiting.body().path("data");
        assertThat(data.path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(data.path("payment").path("paymentCode").asText()).matches("\\d{9}");
        assertThat(data.path("voucher").isNull()).isTrue();

        // The simulated customer pays after the configured delay (none here).
        Call paid = partner("inquire", Map.of("merchant", "MWOS", "by", "PARTNER_REFERENCE",
                "partnerReference", reference));
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.body().path("data").path("status").asText()).isEqualTo("ISSUED");
        assertThat(paid.body().path("data").path("voucher").path("value").decimalValue())
                .isEqualByComparingTo("10.00");
        assertThat(paid.body().path("data").path("voucher").path("voucherId").asText()).isNotBlank();
        assertThat(paid.body().path("data").path("voucher").path("code").isNull()).isTrue();
    }

    @Test
    void theAppChannel_sellsLikeOnline_andRedeems() throws Exception {
        String phone = newPhone();
        String reference = "MWOS-APP-" + UUID.randomUUID();
        Call awaiting = partner("generate", sale(reference, "app", phone, "10.00", Map.of("method", "INNBUCKS_CODE")));
        assertThat(awaiting.status()).as(awaiting.body().toString()).isEqualTo(202);

        Call paid = partner("inquire", Map.of("merchant", "MWOS", "by", "PARTNER_REFERENCE",
                "partnerReference", reference));
        assertThat(paid.body().path("data").path("status").asText()).isEqualTo("ISSUED");
        assertThat(paid.body().path("data").path("channel").asText()).isEqualTo("APP");
        assertThat(paid.body().path("data").path("voucher").path("code").isNull()).isTrue();
        String code = voucherRepository.findById(UUID.fromString(
                paid.body().path("data").path("voucher").path("voucherId").asText())).orElseThrow().getCode();

        Map<String, Object> use = redeem(code, "4.00", "APP-USE-1", phone);
        use.put("channel", "APP");
        Call used = partner("redeem", use);
        assertThat(used.status()).as(used.body().toString()).isEqualTo(200);
        assertThat(used.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("6.00");

        Call cashInApp = partner("generate", sale("R-" + UUID.randomUUID(), "APP", newPhone(), "5.00",
                Map.of("method", "CASH")));
        assertThat(cashInApp.status()).isEqualTo(400);
        assertThat(cashInApp.code()).isEqualTo("FUNDING_METHOD_NOT_ALLOWED");

        Map<String, Object> elsewhere = cashSale("R-" + UUID.randomUUID(), newPhone(), "5.00");
        elsewhere.put("channel", "USSD");
        Call refused = partner("generate", elsewhere);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().path("data").path("channel").asText()).isEqualTo("must be POS, ONLINE, APP or API");
    }

    /** API is what the middleware sends for a third-party integrator with no channel: the ONLINE rules. */
    @Test
    void theApiChannel_followsTheOnlineRules() throws Exception {
        String phone = newPhone();
        Map<String, Object> collected = sale("MWOS-API-" + UUID.randomUUID(), "api", phone, "10.00",
                Map.of("method", "MERCHANT_COLLECTED", "collectedVia", "BETTING_WALLET",
                        "collectionRef", "BW-" + UUID.randomUUID()));
        collected.put("outlet", "HRE-001");
        collected.put("terminalId", "T-88231");
        Call sold = partner("generate", collected);
        assertThat(sold.status()).as(sold.body().toString()).isEqualTo(201);
        JsonNode data = sold.body().path("data");
        assertThat(data.path("status").asText()).isEqualTo("ISSUED");
        assertThat(data.path("channel").asText()).isEqualTo("API");
        // Not a till: no outlet or terminal is kept, and the code goes only to the customer's phone.
        assertThat(data.path("outlet").asText(null)).isNull();
        assertThat(data.path("terminalId").asText(null)).isNull();
        assertThat(data.path("voucher").path("code").asText(null)).isNull();
        UUID voucherId = UUID.fromString(data.path("voucher").path("voucherId").asText());
        String code = voucherRepository.findById(voucherId).orElseThrow().getCode();

        Map<String, Object> use = redeem(code, "4.00", "API-USE-" + UUID.randomUUID(), phone);
        use.put("channel", "API");
        Call used = partner("redeem", use);
        assertThat(used.status()).as(used.body().toString()).isEqualTo(200);
        assertThat(used.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(redemptionRepository.findByVoucherIdOrderByRedeemedAtDesc(voucherId).getFirst().getChannel())
                .isEqualTo("API");

        // An InnBucks code fits; cash and card need POS.
        Call awaiting = partner("generate", sale("R-" + UUID.randomUUID(), "API", newPhone(), "5.00",
                Map.of("method", "INNBUCKS_CODE")));
        assertThat(awaiting.status()).as(awaiting.body().toString()).isEqualTo(202);
        assertThat(partner("generate", sale("R-" + UUID.randomUUID(), "API", newPhone(), "5.00",
                Map.of("method", "CASH"))).code()).isEqualTo("FUNDING_METHOD_NOT_ALLOWED");
        assertThat(partner("generate", sale("R-" + UUID.randomUUID(), "API", newPhone(), "5.00",
                Map.of("method", "CARD_SWIPE", "approvalCode", "A1B2C3"))).code())
                .isEqualTo("FUNDING_METHOD_NOT_ALLOWED");
    }

    @Test
    void refusedSales_recordNothingOrCloseTheAttempt() throws Exception {
        Call declined = partner("generate", cashSale("SIMFAIL-" + UUID.randomUUID(), newPhone(), "20.00"));
        assertThat(declined.status()).isEqualTo(422);
        assertThat(declined.code()).isEqualTo("FLOAT_INSUFFICIENT");
        assertThat(declined.body().path("data").path("status").asText()).isEqualTo("FAILED");

        Call unreachable = partner("generate", cashSale("SIMDOWN-" + UUID.randomUUID(), newPhone(), "20.00"));
        assertThat(unreachable.status()).isEqualTo(503);
        assertThat(unreachable.code()).isEqualTo("PROVIDER_UNAVAILABLE");

        Map<String, Object> otherMerchant = new LinkedHashMap<>(cashSale("R-" + UUID.randomUUID(), newPhone(), "5.00"));
        otherMerchant.put("merchant", "NOT-LINKED");
        Call notLinked = partner("generate", otherMerchant);
        assertThat(notLinked.status()).isEqualTo(403);
        assertThat(notLinked.code()).isEqualTo("MERCHANT_NOT_PERMITTED");

        Call cashOnline = partner("generate", sale("R-" + UUID.randomUUID(), "ONLINE", newPhone(), "5.00",
                Map.of("method", "CASH")));
        assertThat(cashOnline.status()).isEqualTo(400);
        assertThat(cashOnline.code()).isEqualTo("FUNDING_METHOD_NOT_ALLOWED");

        Call noSlip = partner("generate", sale("R-" + UUID.randomUUID(), "POS", newPhone(), "5.00",
                Map.of("method", "CARD_SWIPE")));
        assertThat(noSlip.status()).isEqualTo(400);
        assertThat(noSlip.code()).isEqualTo("APPROVAL_CODE_REQUIRED");

        Call tooBig = partner("generate", cashSale("R-" + UUID.randomUUID(), newPhone(), "500.01"));
        assertThat(tooBig.status()).isEqualTo(422);
        assertThat(tooBig.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(tooBig.body().path("data").path("limit").asText()).isEqualTo("MAX_VALUE");

        Call fieldError = partner("generate", Map.of("merchant", "MWOS"));
        assertThat(fieldError.status()).isEqualTo(400);
        assertThat(fieldError.code()).isEqualTo("400 BAD_REQUEST");
        assertThat(fieldError.body().path("data").has("partnerReference")).isTrue();
    }

    @Test
    void theSameSaleTwiceAtOneTerminal_isAskedAbout_thenAllowedWhenConfirmed() throws Exception {
        String phone = newPhone();
        assertThat(partner("generate", cashSale("R-" + UUID.randomUUID(), phone, "7.00")).status()).isEqualTo(201);

        Call again = partner("generate", cashSale("R-" + UUID.randomUUID(), phone, "7.00"));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("POSSIBLE_DUPLICATE");

        Map<String, Object> confirmed = new LinkedHashMap<>(cashSale("R-" + UUID.randomUUID(), phone, "7.00"));
        confirmed.put("confirmDuplicate", true);
        assertThat(partner("generate", confirmed).status()).isEqualTo(201);
    }

    @Test
    void theMerchantIsHeldInEscrow_soNoOtherPathIssuesOrSpendsItsVouchers() throws Exception {
        String phone = newPhone();
        Call issued = partner("generate", cashSale("MWOS-POS-" + UUID.randomUUID(), phone, "20.00"));
        assertThat(issued.status()).isEqualTo(201);
        UUID voucherId = UUID.fromString(issued.body().path("data").path("voucher").path("voucherId").asText());
        String code = voucherRepository.findById(voucherId).orElseThrow().getCode();
        String admin = TestJwtFactory.builder("owner@test.local").role("SUPER_ADMIN").sign(jwtSecret);

        MvcResult issue = mockMvc.perform(post("/loyalty/vouchers/issue")
                .header("Authorization", bearer(admin)).header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("merchantId", merchantId.toString(), "value", 10,
                        "currency", "USD", "assigneePhone", phone)))).andReturn();
        assertThat(issue.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(issue.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("ESCROW_MERCHANT");

        MvcResult order = mockMvc.perform(post("/loyalty/vouchers/purchase")
                .header("Authorization", bearer(admin)).header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("merchantId", merchantId.toString(), "value", 10,
                        "currency", "USD", "assigneePhone", phone)))).andReturn();
        assertThat(order.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(order.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("ESCROW_MERCHANT");

        // A partner voucher is spent only through the partner, which owes the merchant from holding.
        MvcResult tillRedeem = mockMvc.perform(post("/loyalty/vouchers/redeem")
                .header("Authorization", bearer(admin)).header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("merchantId", merchantId.toString(), "code", code))))
                .andReturn();
        assertThat(tillRedeem.getResponse().getStatus()).isEqualTo(404);
        assertThat(voucherRepository.findById(voucherId).orElseThrow().getRemainingBalance())
                .isEqualByComparingTo("20.00");
    }

    // --------------------------------------------------------- authentication

    @Test
    void aSignedRequestIsUsedOnce_fromTheListedAddress_forItsOwnOperation_withALiveKey() throws Exception {
        Map<String, Object> body = Map.of("merchant", "MWOS", "by", "PARTNER_REFERENCE",
                "partnerReference", "unknown-" + UUID.randomUUID());
        String jws = sign("inquire", body);
        assertThat(send("inquire", jws, CALLER_IP).status()).isEqualTo(404);

        Call replayed = send("inquire", jws, CALLER_IP);
        assertThat(replayed.status()).isEqualTo(401);
        assertThat(replayed.code()).isEqualTo("PARTNER_AUTH_REJECTED");
        assertThat(replayed.body().path("message").asText()).isEqualTo("Request rejected.");

        assertThat(send("inquire", sign("inquire", body), "198.51.100.99").status()).isEqualTo(401);
        assertThat(send("redeem", sign("inquire", body), CALLER_IP).status()).isEqualTo(401);

        MvcResult asJson = mockMvc.perform(post("/loyalty/partner/vouchers/inquire")
                .header("X-Real-IP", CALLER_IP).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
        assertThat(asJson.getResponse().getStatus()).isEqualTo(415);

        admin(post("/loyalty/admin/voucher-partners/" + partnerId + "/keys/" + kid + "/revoke"), null, 200);
        assertThat(send("inquire", sign("inquire", body), CALLER_IP).status()).isEqualTo(401);
    }

    @Test
    void theAdminEndpointsAreSuperAdminOnly() throws Exception {
        String merchantAdmin = TestJwtFactory.builder("ma@test.local").role("MERCHANT_ADMIN").sign(jwtSecret);
        MvcResult refused = mockMvc.perform(post("/loyalty/admin/voucher-partners")
                .header("Authorization", bearer(merchantAdmin)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "x", "issuer", "x-" + UUID.randomUUID()))))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- helpers

    private record Call(int status, JsonNode body) {
        String code() {
            return body.path("code").asText();
        }
    }

    private Map<String, Object> cashSale(String reference, String phone, String amount) {
        return sale(reference, "POS", phone, amount, Map.of("method", "CASH"));
    }

    private Map<String, Object> sale(String reference, String channel, String phone, String amount,
                                     Map<String, Object> funding) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("merchant", "MWOS");
        s.put("partnerReference", reference);
        s.put("channel", channel);
        if ("POS".equals(channel)) {
            s.put("outlet", "HRE-001");
            s.put("terminalId", "T-88231");
        }
        s.put("amount", new BigDecimal(amount));
        s.put("currency", "USD");
        s.put("customer", Map.of("phone", phone, "name", "Tawanda Mpofu"));
        s.put("funding", funding);
        // Most cases spend part of a voucher; the default is SINGLE.
        s.put("usage", "MULTIPLE");
        return s;
    }

    private Map<String, Object> redeem(String code, String amount, String reference, String phone) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("merchant", "MWOS");
        r.put("code", code);
        if (amount != null) {
            r.put("amount", new BigDecimal(amount));
        }
        r.put("currency", "USD");
        r.put("reference", reference);
        r.put("endUserRef", "MWOS-ACC-1");
        r.put("endUserPhone", phone);
        r.put("channel", "ONLINE");
        return r;
    }

    private String sign(String op, Object body) {
        return PartnerJws.sign(PartnerJws.header("ES256", kid), PartnerJws.claims(issuer, AUDIENCE, op, body),
                keys.getPrivate());
    }

    private Call partner(String op, Object body) throws Exception {
        return send(op, sign(op, body), CALLER_IP);
    }

    private Call send(String op, String jws, String fromIp) throws Exception {
        MvcResult r = mockMvc.perform(post("/loyalty/partner/vouchers/" + op)
                .header("X-Real-IP", fromIp)
                .contentType("application/jose")
                .content(jws)).andReturn();
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return new Call(r.getResponse().getStatus(), json.readTree(r.getResponse().getContentAsString()));
    }

    private JsonNode admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                           Object body, int expectedStatus) throws Exception {
        String token = TestJwtFactory.builder("owner@test.local").role("SUPER_ADMIN").sign(jwtSecret);
        request.header("Authorization", bearer(token));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static String newPhone() {
        return "+26377" + String.format("%07d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 10_000_000L));
    }
}
