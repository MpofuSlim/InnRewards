package com.innbucks.loyaltyservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.PartnerJtiStore;
import com.innbucks.loyaltyservice.service.InvoicingService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Tenant-to-tenant partnerships (V68) end to end: MWOS and Bola Bet, two
 * programs with no login of their own, become partners through a SUPER_ADMIN
 * acting for each; the InnBucks middleware then sells an MWOS voucher with
 * {@code redeemableAt} "ANY", which works at Bola Bet too, and spends it there, through real
 * ES256 requests, the real filter chain, the services and Postgres. Bola Bet is
 * the one paid and billed for that use.
 */
@TestPropertySource(properties = {
        "loyalty.voucher-partner.enabled=true",
        "loyalty.voucher-partner.audience=" + VoucherPartnershipFlowIT.AUDIENCE,
        "loyalty.voucher-partner.funding=SIMULATED",
        "loyalty.voucher-partner.simulated-delay=PT0S",
        "loyalty.voucher-partner.reconcile-interval=PT1H"
})
class VoucherPartnershipFlowIT extends ControllerSecurityTestBase {

    static final String AUDIENCE = "innbucks-loyalty-vouchers:partnership-it";
    private static final String CALLER_IP = "203.0.113.20";

    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;
    @MockitoBean PartnerJtiStore jtiStore;

    @Autowired MerchantService merchantService;
    @Autowired MerchantRepository merchantRepository;
    @Autowired VoucherRepository voucherRepository;
    @Autowired VoucherRedemptionRepository redemptionRepository;
    @Autowired InvoicingService invoicingService;
    @Autowired ObjectMapper json;

    private final KeyPair keys = PartnerJws.ecKeyPair();
    private String issuer;
    private String kid;

    private UUID mwosTenant;
    private UUID bolaTenant;
    private UUID strangerTenant;
    private UUID mwos;
    private UUID bola;
    private UUID stranger;
    private String mwosCode;
    private String bolaCode;
    private String strangerCode;
    private String partnerId;

    @BeforeEach
    void twoProgramsAndAStranger() throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        when(jtiStore.firstUse(anyString(), any())).thenAnswer(inv -> seen.add(inv.getArgument(0)));

        mwosTenant = newTenant("mwos");
        bolaTenant = newTenant("bola");
        strangerTenant = newTenant("stranger");
        // MWOS pays 0.05 per voucher issued and 0.25 per use; Bola Bet 0.10 per use.
        mwos = merchant(mwosTenant, "MWOS", "0.05", "0.25");
        bola = merchant(bolaTenant, "Bola Bet", "0.07", "0.10");
        stranger = merchant(strangerTenant, "Stranger Bet", "0.07", "0.10");

        issuer = "innbucks-middleware-" + UUID.randomUUID();
        kid = "mw-it-" + UUID.randomUUID();
        JsonNode created = superAdmin(post("/loyalty/admin/voucher-partners"), null, Map.of(
                "name", "InnBucks middleware", "issuer", issuer, "allowedIps", List.of(CALLER_IP)), 201);
        partnerId = created.path("data").path("partnerId").asText();
        superAdmin(post("/loyalty/admin/voucher-partners/" + partnerId + "/keys"), null, Map.of(
                "kid", kid, "alg", "ES256", "publicKeyPem", PartnerJws.pem(keys.getPublic())), 200);
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        mwosCode = "MWOS" + suffix;
        bolaCode = "BOLA" + suffix;
        strangerCode = "STRG" + suffix;
        link(partnerId, mwos, mwosCode);
        link(partnerId, bola, bolaCode);
        link(partnerId, stranger, strangerCode);
    }

    // ------------------------------------------------------------------ flows

    @Test
    void aVoucherSoldAtMwos_isUsedAtBolaBet_andBolaBetIsPaidAndBilledForTheUse() throws Exception {
        partners();
        String phone = newPhone();

        Call sold = partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), phone, "20.00", "ANY"));
        assertThat(sold.status()).as(sold.body().toString()).isEqualTo(201);
        JsonNode view = sold.body().path("data").path("voucher");
        // The stranger is served by the same partner but is no partner of MWOS.
        assertThat(view.path("redeemableAt")).hasSize(2);
        assertThat(view.path("redeemableAt").get(0).path("merchant").asText()).isEqualTo(mwosCode);
        assertThat(view.path("redeemableAt").get(1).path("merchant").asText()).isEqualTo(bolaCode);
        assertThat(view.path("redeemableAt").get(1).path("name").asText()).isEqualTo("Bola Bet");
        String code = view.path("code").asText();
        Voucher stored = voucherRepository.findById(UUID.fromString(view.path("voucherId").asText())).orElseThrow();
        assertThat(stored.getRedeemableMerchantIds()).containsExactly(bola);

        // Bola Bet can see it and spend it.
        Call balance = partner("inquire", Map.of("merchant", bolaCode, "by", "CODE", "code", code,
                "endUserRef", "BOLA-ACC-1", "endUserPhone", phone));
        assertThat(balance.status()).as(balance.body().toString()).isEqualTo(200);
        assertThat(balance.body().path("data").path("redeemableAt")).hasSize(2);
        Call used = partner("redeem", redeem(bolaCode, code, "5.00", "BOLA-USE-1", phone));
        assertThat(used.status()).as(used.body().toString()).isEqualTo(200);
        assertThat(used.body().path("data").path("remainingBalance").decimalValue()).isEqualByComparingTo("15.00");

        // The use is Bola Bet's: it is the merchant paid from holding...
        VoucherRedemption r = redemptionRepository.findById(
                UUID.fromString(used.body().path("data").path("redemptionId").asText())).orElseThrow();
        assertThat(r.getMerchantId()).isEqualTo(bola);
        // The row stays in the voucher's own program, like every redemption row.
        assertThat(r.getTenantId()).isEqualTo(mwosTenant);
        assertThat(r.getSettlementStatus()).isEqualTo(VoucherRedemption.SettlementStatus.SETTLED);

        // ... and the one billed the redemption fee; MWOS pays the issue fee only.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Invoice bolaBill = invoicingService.generate(merchantRepository.findById(bola).orElseThrow(), today, today)
                .orElseThrow();
        assertThat(bolaBill.getVouchersRedeemed()).isEqualTo(1);
        assertThat(bolaBill.getTotalAmount()).isEqualByComparingTo("0.10");
        Invoice mwosBill = invoicingService.generate(merchantRepository.findById(mwos).orElseThrow(), today, today)
                .orElseThrow();
        assertThat(mwosBill.getVouchersIssued()).isEqualTo(1);
        assertThat(mwosBill.getVouchersRedeemed()).isZero();
        assertThat(mwosBill.getTotalAmount()).isEqualByComparingTo("0.05");

        // MWOS still takes it too.
        assertThat(partner("redeem", redeem(mwosCode, code, "5.00", "MWOS-USE-1", phone)).status()).isEqualTo(200);
    }

    @Test
    void withoutRedeemableAt_aVoucherIsUnknownAtAnyOtherMerchant() throws Exception {
        partners();
        String phone = newPhone();
        Call sold = partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), phone, "20.00"));
        assertThat(sold.status()).isEqualTo(201);
        JsonNode view = sold.body().path("data").path("voucher");
        assertThat(view.path("redeemableAt")).hasSize(1);
        String code = view.path("code").asText();

        Call atBola = partner("redeem", redeem(bolaCode, code, "5.00", "BOLA-USE-2", phone));
        assertThat(atBola.status()).isEqualTo(404);
        Call seen = partner("inquire", Map.of("merchant", bolaCode, "by", "CODE", "code", code,
                "endUserRef", "BOLA-ACC-1", "endUserPhone", phone));
        assertThat(seen.status()).isEqualTo(404);
        // Naming only the issuer is the same as leaving it out.
        Map<String, Object> explicit = sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00", mwosCode);
        assertThat(partner("generate", explicit).body().path("data").path("voucher").path("redeemableAt"))
                .hasSize(1);
    }

    @Test
    void any_coversSisterMerchantsAndPartnersThisPartnerServes_only() throws Exception {
        // A sister merchant of MWOS, served by the same partner: in, with no partnership needed.
        UUID mwosSister = merchant(mwosTenant, "MWOS Bulawayo", "0.05", "0.25");
        String sisterCode = "SIS" + mwosCode;
        link(partnerId, mwosSister, sisterCode);
        // A second Bola Bet merchant in the partnership but served by ANOTHER partner: out,
        // because a partner voucher is redeemed through the partner that sold it.
        UUID bolaElsewhere = merchant(bolaTenant, "Bola Bet Online", "0.07", "0.10");
        JsonNode other = superAdmin(post("/loyalty/admin/voucher-partners"), null, Map.of(
                "name", "Another integrator", "issuer", "other-" + UUID.randomUUID(),
                "allowedIps", List.of(CALLER_IP)), 201);
        link(other.path("data").path("partnerId").asText(), bolaElsewhere, "ELSE" + bolaCode);
        JsonNode invited = superAdmin(post("/loyalty/partnerships"), mwosTenant,
                invite(tenantCode(bolaTenant), mwos), 201);
        superAdmin(post("/loyalty/partnerships/" + invited.path("data").path("id").asText() + "/accept"), bolaTenant,
                Map.of("merchantIds", List.of(bola.toString(), bolaElsewhere.toString())), 200);

        Call sold = partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00", "ANY"));
        assertThat(sold.status()).as(sold.body().toString()).isEqualTo(201);
        assertThat(sold.body().path("data").path("voucher").path("redeemableAt"))
                .extracting(n -> n.path("merchant").asText())
                .containsExactly(mwosCode, bolaCode, sisterCode);
    }

    @Test
    void any_withNoPartnerships_worksAtTheIssuerOnly() throws Exception {
        Call sold = partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00", "ANY"));
        assertThat(sold.status()).as(sold.body().toString()).isEqualTo(201);
        assertThat(sold.body().path("data").path("voucher").path("redeemableAt")).hasSize(1);
        Voucher stored = voucherRepository.findById(UUID.fromString(
                sold.body().path("data").path("voucher").path("voucherId").asText())).orElseThrow();
        assertThat(stored.getRedeemableMerchantIds()).isNull();
    }

    @Test
    void namedMerchants_areChecked_andEveryRefusedCodeIsNamed_andNothingIsRecorded() throws Exception {
        partners();
        String reference = "MWOS-" + UUID.randomUUID();
        Call refused = partner("generate", sale(mwosCode, reference, newPhone(), "20.00",
                bolaCode, strangerCode, "NOSUCHCODE"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("MERCHANT_NOT_A_PARTNER");
        assertThat(refused.body().path("data").path("merchants")).extracting(JsonNode::asText)
                .containsExactly(strangerCode, "NOSUCHCODE");

        // Nothing was recorded: the same reference naming a partner goes through.
        Call sold = partner("generate", sale(mwosCode, reference, newPhone(), "20.00", bolaCode.toLowerCase(),
                mwosCode));
        assertThat(sold.status()).as(sold.body().toString()).isEqualTo(201);
        assertThat(sold.body().path("data").path("voucher").path("redeemableAt"))
                .extracting(n -> n.path("merchant").asText()).containsExactly(mwosCode, bolaCode);
    }

    @Test
    void any_cannotBeCombinedWithCodes_norBeAMerchantCode() throws Exception {
        Call mixed = partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00",
                "ANY", bolaCode));
        assertThat(mixed.status()).isEqualTo(400);
        assertThat(mixed.body().path("data").path("redeemableAt").asText())
                .isEqualTo("ANY cannot be combined with merchant codes");

        UUID another = merchant(mwosTenant, "MWOS Any", "0.05", "0.25");
        JsonNode bad = superAdmin(post("/loyalty/admin/voucher-partners/" + partnerId + "/merchants"), null,
                Map.of("merchantId", another.toString(), "merchantCode", "any"), 400);
        assertThat(bad.path("code").asText()).isEqualTo("BAD_MERCHANT_CODE");
    }

    @Test
    void aSuspendedPartnership_stopsUseAndSales_untilReinstated() throws Exception {
        String partnership = partners();
        String phone = newPhone();
        String code = partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), phone, "20.00", "ANY"))
                .body().path("data").path("voucher").path("code").asText();

        superAdmin(post("/loyalty/admin/partnerships/" + partnership + "/suspend"), null,
                Map.of("reason", "Settlement dispute under review"), 200);

        Call stopped = partner("redeem", redeem(bolaCode, code, "5.00", "BOLA-USE-3", phone));
        assertThat(stopped.status()).isEqualTo(409);
        assertThat(stopped.code()).isEqualTo("PARTNERSHIP_SUSPENDED");
        // An ANY voucher sold meanwhile does not include Bola Bet, and naming it is refused.
        assertThat(partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00", "ANY"))
                .body().path("data").path("voucher").path("redeemableAt")).hasSize(1);
        assertThat(partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00", bolaCode))
                .code()).isEqualTo("MERCHANT_NOT_A_PARTNER");
        // The issuer still takes its own voucher.
        assertThat(partner("redeem", redeem(mwosCode, code, "5.00", "MWOS-USE-3", phone)).status()).isEqualTo(200);

        superAdmin(post("/loyalty/admin/partnerships/" + partnership + "/reinstate"), null, null, 200);
        assertThat(partner("redeem", redeem(bolaCode, code, "5.00", "BOLA-USE-4", phone)).status()).isEqualTo(200);
    }

    @Test
    void anEndedPartnership_keepsVouchersAlreadySold_butAddsNoMore_andAReplayStillReplays() throws Exception {
        String partnership = partners();
        String phone = newPhone();
        String reference = "MWOS-" + UUID.randomUUID();
        Map<String, Object> request = sale(mwosCode, reference, phone, "20.00", "ANY");
        String code = partner("generate", request).body().path("data").path("voucher").path("code").asText();

        superAdmin(post("/loyalty/partnerships/" + partnership + "/end"), bolaTenant, null, 200);

        assertThat(partner("redeem", redeem(bolaCode, code, "5.00", "BOLA-USE-5", phone)).status()).isEqualTo(200);
        assertThat(partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00", "ANY"))
                .body().path("data").path("voucher").path("redeemableAt")).hasSize(1);
        Call replay = partner("generate", request);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("data").path("replayed").asBoolean()).isTrue();
        // The replay answers with the list fixed at sale.
        assertThat(replay.body().path("data").path("voucher").path("redeemableAt")).hasSize(2);
    }

    @Test
    void theSameReference_withAnotherRedeemableAt_isADifferentRequest() throws Exception {
        partners();
        String reference = "MWOS-" + UUID.randomUUID();
        String phone = newPhone();
        assertThat(partner("generate", sale(mwosCode, reference, phone, "20.00", "ANY")).status()).isEqualTo(201);
        assertThat(partner("generate", sale(mwosCode, reference, phone, "20.00")).code())
                .isEqualTo("IDEMPOTENCY_MISMATCH");
    }

    // --------------------------------------------------------- the partnership

    @Test
    void theInvitationRules() throws Exception {
        String bolaTenantCode = tenantCode(bolaTenant);
        String mwosTenantCode = tenantCode(mwosTenant);

        assertThat(superAdmin(post("/loyalty/partnerships"), mwosTenant,
                invite(mwosTenantCode, mwos), 400).path("code").asText()).isEqualTo("PARTNERSHIP_SELF");
        assertThat(superAdmin(post("/loyalty/partnerships"), mwosTenant,
                invite("no-such-tenant", mwos), 404).path("message").asText()).isEqualTo("tenant not found");
        // A merchant of another program is not the inviter's to put in.
        superAdmin(post("/loyalty/partnerships"), mwosTenant, invite(bolaTenantCode, bola), 404);

        JsonNode invited = superAdmin(post("/loyalty/partnerships"), mwosTenant, invite(bolaTenantCode, mwos), 201);
        String id = invited.path("data").path("id").asText();
        assertThat(invited.path("data").path("status").asText()).isEqualTo("INVITED");
        assertThat(invited.path("data").path("autoAccepted").asBoolean()).isFalse();
        assertThat(invited.path("data").path("invitedBy").asText()).isEqualTo("owner@test.local");

        // One live partnership per pair, whichever side asks.
        assertThat(superAdmin(post("/loyalty/partnerships"), bolaTenant,
                invite(mwosTenantCode, bola), 409).path("code").asText()).isEqualTo("PARTNERSHIP_EXISTS");
        // Only the invited side answers; the inviter cannot accept for it.
        assertThat(superAdmin(post("/loyalty/partnerships/" + id + "/accept"), mwosTenant,
                Map.of("merchantIds", List.of(mwos.toString())), 403).path("code").asText())
                .isEqualTo("NOT_INVITED_TENANT");
        // A tenant not in it does not see it.
        superAdmin(post("/loyalty/partnerships/" + id + "/end"), strangerTenant, null, 404);
        // Until it is accepted, an ANY voucher does not include the other side.
        assertThat(partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00", "ANY"))
                .body().path("data").path("voucher").path("redeemableAt")).hasSize(1);

        JsonNode accepted = superAdmin(post("/loyalty/partnerships/" + id + "/accept"), bolaTenant,
                Map.of("merchantIds", List.of(bola.toString())), 200);
        assertThat(accepted.path("data").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(accepted.path("data").path("invitee").path("merchants").get(0).path("name").asText())
                .isEqualTo("Bola Bet");
        assertThat(superAdmin(post("/loyalty/partnerships/" + id + "/accept"), bolaTenant,
                Map.of("merchantIds", List.of(bola.toString())), 409).path("code").asText())
                .isEqualTo("PARTNERSHIP_NOT_PENDING");

        // Each side lists it.
        assertThat(superAdmin(get("/loyalty/partnerships"), bolaTenant, null, 200).path("data")).hasSize(1);
        assertThat(superAdmin(get("/loyalty/partnerships"), strangerTenant, null, 200).path("data")).isEmpty();

        // Accepted, an ANY voucher includes Bola Bet.
        assertThat(partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00", "ANY"))
                .body().path("data").path("voucher").path("redeemableAt")).hasSize(2);

        // Bola Bet takes its merchant out: new MWOS vouchers no longer include it.
        UUID bolaTwo = merchant(bolaTenant, "Bola Bet Two", "0.07", "0.10");
        superAdmin(put("/loyalty/partnerships/" + id + "/merchants"), bolaTenant,
                Map.of("merchantIds", List.of(bolaTwo.toString())), 200);
        assertThat(partner("generate", sale(mwosCode, "MWOS-" + UUID.randomUUID(), newPhone(), "20.00", "ANY"))
                .body().path("data").path("voucher").path("redeemableAt")).hasSize(1);
    }

    @Test
    void aMerchantAdmin_putsInOnlyMerchantsItAdministers_andSeesNoNames() throws Exception {
        UUID org = TestJwtFactory.DEFAULT_ORGANIZATION;
        Tenant t = new Tenant();
        t.setCode("org-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Org Program");
        t.setOrganizationId(org);
        UUID orgTenant = tenantRepository.save(t).getId();
        UUID own = MerchantFixtures.createAsPlatform(merchantService, orgTenant, new Dtos.MerchantRequest(
                "Own " + UUID.randomUUID(), null, "USD", null, null, null, null, null, null, org)).id();
        UUID notOwn = MerchantFixtures.createAsPlatform(merchantService, orgTenant, new Dtos.MerchantRequest(
                "Not own " + UUID.randomUUID(), null, "USD", null, null, null, null, null, null,
                UUID.randomUUID())).id();
        String admin = bearer(TestJwtFactory.merchantAdmin("owner@org.co.zw", jwtSecret));

        MvcResult refused = mockMvc.perform(post("/loyalty/partnerships").header("Authorization", admin)
                .header("X-Tenant-Id", orgTenant.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(invite(tenantCode(bolaTenant), notOwn)))).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(refused.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("NOT_MERCHANT_OWNER");

        MvcResult ok = mockMvc.perform(post("/loyalty/partnerships").header("Authorization", admin)
                .header("X-Tenant-Id", orgTenant.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(invite(tenantCode(bolaTenant), own)))).andReturn();
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode body = json.readTree(ok.getResponse().getContentAsString());
        JsonNode invitedBy = body.path("data").path("invitedBy");
        assertThat(invitedBy.isNull() || invitedBy.isMissingNode()).as("people are shown to InnBucks only").isTrue();

        // Suspending is InnBucks' alone.
        MvcResult suspend = mockMvc.perform(post("/loyalty/admin/partnerships/" + body.path("data").path("id")
                        .asText() + "/suspend").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andReturn();
        assertThat(suspend.getResponse().getStatus()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- helpers

    /** MWOS invites Bola Bet and Bola Bet accepts, a SUPER_ADMIN acting for each. Returns the partnership id. */
    private String partners() throws Exception {
        JsonNode invited = superAdmin(post("/loyalty/partnerships"), mwosTenant, invite(tenantCode(bolaTenant), mwos),
                201);
        String id = invited.path("data").path("id").asText();
        superAdmin(post("/loyalty/partnerships/" + id + "/accept"), bolaTenant,
                Map.of("merchantIds", List.of(bola.toString())), 200);
        return id;
    }

    private static Map<String, Object> invite(String tenantCode, UUID merchantId) {
        return Map.of("partnerTenantCode", tenantCode, "merchantIds", List.of(merchantId.toString()));
    }

    private String tenantCode(UUID tenantId) {
        return tenantRepository.findById(tenantId).orElseThrow().getCode();
    }

    private UUID merchant(UUID tenantId, String name, String issueFee, String redeemFee) {
        return MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest(name, "Betting", "USD", Merchant.BillingCycle.DAILY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal(issueFee), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal(redeemFee), null))).id();
    }

    private void link(String partnerId, UUID merchantId, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId.toString());
        body.put("merchantCode", code);
        superAdmin(post("/loyalty/admin/voucher-partners/" + partnerId + "/merchants"), null, body, 200);
    }

    private record Call(int status, JsonNode body) {
        String code() {
            return body.path("code").asText();
        }
    }

    private Map<String, Object> sale(String merchantCode, String reference, String phone, String amount,
                                     String... redeemableAt) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("merchant", merchantCode);
        s.put("partnerReference", reference);
        s.put("channel", "POS");
        s.put("terminalId", "T-1");
        s.put("amount", new BigDecimal(amount));
        s.put("currency", "USD");
        s.put("customer", Map.of("phone", phone, "name", "Tawanda Mpofu"));
        s.put("funding", Map.of("method", "CASH"));
        // Most cases spend part of a voucher; the default is SINGLE.
        s.put("usage", "MULTIPLE");
        if (redeemableAt.length == 1) {
            s.put("redeemableAt", redeemableAt[0]);        // a single string is accepted
        } else if (redeemableAt.length > 1) {
            s.put("redeemableAt", List.of(redeemableAt));
        }
        return s;
    }

    private Map<String, Object> redeem(String merchantCode, String code, String amount, String reference,
                                       String phone) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("merchant", merchantCode);
        r.put("code", code);
        r.put("amount", new BigDecimal(amount));
        r.put("currency", "USD");
        r.put("reference", reference);
        r.put("endUserRef", "ACC-1");
        r.put("endUserPhone", phone);
        r.put("channel", "ONLINE");
        return r;
    }

    private Call partner(String op, Object body) throws Exception {
        String jws = PartnerJws.sign(PartnerJws.header("ES256", kid), PartnerJws.claims(issuer, AUDIENCE, op, body),
                keys.getPrivate());
        MvcResult r = mockMvc.perform(post("/loyalty/partner/vouchers/" + op)
                .header("X-Real-IP", CALLER_IP)
                .contentType("application/jose")
                .content(jws)).andReturn();
        return new Call(r.getResponse().getStatus(), json.readTree(r.getResponse().getContentAsString()));
    }

    private JsonNode superAdmin(MockHttpServletRequestBuilder request, UUID tenantId, Object body,
                                int expectedStatus) throws Exception {
        String token = TestJwtFactory.builder("owner@test.local").role("SUPER_ADMIN").sign(jwtSecret);
        request.header("Authorization", bearer(token));
        if (tenantId != null) {
            request.header("X-Tenant-Id", tenantId.toString());
        }
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
