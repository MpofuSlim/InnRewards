package com.innbucks.loyaltyservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.security.PartnerJtiStore;
import com.innbucks.loyaltyservice.security.ReportsPermissions;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.partner.PartnerHoldingReconciliation;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.MerchantFixtures;
import com.innbucks.loyaltyservice.testsupport.PartnerJws;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

/**
 * The partner voucher money views (V70) against real Postgres, fed by real signed
 * partner requests with SIMULATED funding: MWOS sells two vouchers into holding,
 * one is used at MWOS and at its partner Bola Bet, the other expires, and a third
 * code is paid at the redeem (Flow 2). The statements must say who was paid and
 * who is still owed, and the holding reconciliation must account for every cent
 * held, and notice a cent that goes missing.
 *
 * <p>The database is shared with the other ITs, so the reconciliation is checked
 * by how much each figure MOVED across this test's own activity.
 */
@TestPropertySource(properties = {
        "loyalty.voucher-partner.enabled=true",
        "loyalty.voucher-partner.audience=" + PartnerMoneyIT.AUDIENCE,
        "loyalty.voucher-partner.funding=SIMULATED",
        "loyalty.voucher-partner.simulated-delay=PT0S",
        "loyalty.voucher-partner.reconcile-interval=PT1H",
        // The test runs the reconciliation itself.
        "loyalty.voucher-partner.reconciliation.enabled=false"
})
class PartnerMoneyIT extends ControllerSecurityTestBase {

    static final String AUDIENCE = "innbucks-loyalty-vouchers:money-it";
    private static final String CALLER_IP = "203.0.113.31";

    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;
    @MockitoBean PartnerJtiStore jtiStore;

    @Autowired MerchantService merchantService;
    @Autowired PartnerHoldingReconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired ObjectMapper json;

    private final KeyPair keys = PartnerJws.ecKeyPair();
    private UUID mwosTenant;
    private UUID bolaTenant;
    private UUID mwos;
    private UUID bola;
    private UUID mwosOrganization;
    private String mwosCode;
    private String bolaCode;
    private String issuer;
    private String kid;

    @BeforeEach
    void twoPartnerMerchants() throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        when(jtiStore.firstUse(anyString(), any())).thenAnswer(inv -> seen.add(inv.getArgument(0)));

        mwosTenant = newTenant("mwos");
        bolaTenant = newTenant("bola");
        mwos = merchant(mwosTenant, "MWOS");
        bola = merchant(bolaTenant, "Bola Bet");
        mwosOrganization = UUID.randomUUID();
        jdbc.update("UPDATE merchants SET organization_id = ? WHERE id = ?", mwosOrganization, mwos);

        issuer = "innbucks-middleware-" + UUID.randomUUID();
        kid = "mw-money-" + UUID.randomUUID();
        JsonNode created = superAdmin(post("/loyalty/admin/voucher-partners"), null, Map.of(
                "name", "InnBucks middleware", "issuer", issuer, "allowedIps", List.of(CALLER_IP)), 201);
        String partnerId = created.path("data").path("partnerId").asText();
        superAdmin(post("/loyalty/admin/voucher-partners/" + partnerId + "/keys"), null, Map.of(
                "kid", kid, "alg", "ES256", "publicKeyPem", PartnerJws.pem(keys.getPublic())), 200);
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        mwosCode = "MWOS" + suffix;
        bolaCode = "BOLA" + suffix;
        link(partnerId, mwos, mwosCode);
        link(partnerId, bola, bolaCode);

        JsonNode invited = superAdmin(post("/loyalty/partnerships"), mwosTenant, Map.of(
                "partnerTenantCode", tenantRepository.findById(bolaTenant).orElseThrow().getCode(),
                "merchantIds", List.of(mwos.toString())), 201);
        superAdmin(post("/loyalty/partnerships/" + invited.path("data").path("id").asText() + "/accept"),
                bolaTenant, Map.of("merchantIds", List.of(bola.toString())), 200);
    }

    /** What the test does, and the ids it leaves behind. */
    private record Activity(UUID usedAtBola, String voucherA) {}

    /**
     * MWOS sells A (20.00) and B (10.00) for cash. A is used for 5.00 at MWOS
     * (settled at once) and 7.00 at Bola Bet (left unsettled, two hours ago), B
     * expires, and a Flow 2 code of 15.00 is paid at an MWOS agent.
     */
    private Activity spendMoney() throws Exception {
        String phone = newPhone();
        Call a = partner("generate", sale("MWOS-A-" + UUID.randomUUID(), phone, "20.00"));
        assertThat(a.status()).as(a.body().toString()).isEqualTo(201);
        String codeA = a.body().path("data").path("voucher").path("code").asText();
        Call b = partner("generate", sale("MWOS-B-" + UUID.randomUUID(), phone, "10.00"));
        assertThat(b.status()).as(b.body().toString()).isEqualTo(201);
        String voucherB = b.body().path("data").path("voucher").path("voucherId").asText();

        Call atMwos = partner("redeem", redeem(mwosCode, codeA, "5.00", "MWOS-USE-" + UUID.randomUUID(), null));
        assertThat(atMwos.status()).as(atMwos.body().toString()).isEqualTo(200);
        Call atBola = partner("redeem", redeem(bolaCode, codeA, "7.00", "BOLA-USE-" + UUID.randomUUID(), null));
        assertThat(atBola.status()).as(atBola.body().toString()).isEqualTo(200);
        UUID usedAtBola = UUID.fromString(atBola.body().path("data").path("redemptionId").asText());
        // The simulator settles at once; this use is left owed, as if the transfer had not gone through.
        jdbc.update("""
                UPDATE voucher_redemptions SET settlement_status = 'PENDING', settled_at = NULL, settlement_ref = NULL,
                       redeemed_at = now() - interval '2 hours'
                WHERE id = ?""", usedAtBola);
        jdbc.update("UPDATE vouchers SET expires_at = now() - interval '1 day' WHERE id = ?::uuid", voucherB);

        Call flow2 = partner("generate", unpaid("MWOS-APP-" + UUID.randomUUID(), newPhone(), "15.00"));
        assertThat(flow2.status()).as(flow2.body().toString()).isEqualTo(201);
        String code2 = flow2.body().path("data").path("voucher").path("code").asText();
        Map<String, Object> pay = redeem(mwosCode, code2, "15.00", "AGT-" + UUID.randomUUID(),
                Map.of("method", "CASH", "accountNumber", "1001234567890"));
        pay.put("channel", "POS");
        Call paid = partner("redeem", pay);
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        String voucherA = jdbc.queryForObject("SELECT id::text FROM vouchers WHERE code = ?", String.class, codeA);
        return new Activity(usedAtBola, voucherA);
    }

    // ------------------------------------------------------------ reconciliation

    @Test
    void theHoldingAccountsForEveryCent_andAMissingOneIsAMismatch() throws Exception {
        JsonNode before = usd(runByHand());

        Activity act = spendMoney();
        JsonNode after = usd(runByHand());

        // A and B were paid into holding; the Flow 2 code never was.
        assertThat(moved(before, after, "paidIn")).isEqualByComparingTo("30.00");
        assertThat(moved(before, after, "settledOut")).isEqualByComparingTo("5.00");
        assertThat(moved(before, after, "expectedHolding")).isEqualByComparingTo("25.00");
        assertThat(moved(before, after, "outstandingBalance")).isEqualByComparingTo("8.00");
        assertThat(moved(before, after, "expiredBalance")).isEqualByComparingTo("10.00");
        assertThat(moved(before, after, "owedToMerchants")).isEqualByComparingTo("7.00");
        assertThat(moved(before, after, "paidNotIssued")).isEqualByComparingTo("0");
        assertThat(moved(before, after, "ledgerDifference")).isEqualByComparingTo("0");
        assertThat(after.path("stuckSettlements").asInt() - before.path("stuckSettlements").asInt()).isEqualTo(1);
        assertThat(after.path("bankBalance").isNull()).isTrue();
        assertThat(after.path("bankDifference").isNull()).isTrue();

        // The owed use is listed live, for an operator.
        JsonNode view = superAdminGet("/loyalty/partner-vouchers/reconciliation?limit=2").path("data");
        assertThat(view.path("runs")).hasSize(2);
        assertThat(view.path("runs").get(0).path("trigger").asText()).isEqualTo("MANUAL");
        assertThat(view.path("runs").get(0).path("triggeredBy").asText()).isEqualTo("owner@test.local");
        assertThat(view.path("stuckAfterSeconds").asLong()).isEqualTo(3600);
        assertThat(view.path("stuckNow").findValuesAsText("id")).contains(act.usedAtBola().toString());

        // A dollar goes missing from voucher A: our records no longer add up.
        double mismatchesBefore = counter("ledger_mismatch");
        jdbc.update("UPDATE vouchers SET remaining_balance = remaining_balance - 1 WHERE id = ?::uuid",
                act.voucherA());
        try {
            PartnerHoldingReconciliation.Run broken =
                    reconciliation.run(PartnerHoldingReconciliation.Trigger.MANUAL, "it");
            PartnerHoldingReconciliation.Line line = broken.currencies().stream()
                    .filter(l -> l.currency().equals("USD")).findFirst().orElseThrow();
            assertThat(line.ledgerDifference().subtract(after.path("ledgerDifference").decimalValue()))
                    .isEqualByComparingTo("1.00");
            assertThat(line.status()).isEqualTo(PartnerHoldingReconciliation.Status.LEDGER_MISMATCH);
            assertThat(broken.status()).isEqualTo(PartnerHoldingReconciliation.Status.LEDGER_MISMATCH);
            assertThat(counter("ledger_mismatch")).isEqualTo(mismatchesBefore + 1);
            assertThat(meters.get("loyalty.partner.holding.ledger_difference").tag("currency", "USD").gauge()
                    .value()).isEqualTo(line.ledgerDifference().doubleValue());
        } finally {
            jdbc.update("UPDATE vouchers SET remaining_balance = remaining_balance + 1 WHERE id = ?::uuid",
                    act.voucherA());
        }

        // A run is a record: it cannot be changed or removed.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partner_reconciliation_runs", Integer.class))
                .isGreaterThanOrEqualTo(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE partner_reconciliation_runs SET status = 'BALANCED'"))
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("DELETE FROM partner_reconciliation_runs"))
                .hasMessageContaining("append-only");
    }

    // ---------------------------------------------------------------- statements

    @Test
    void eachMerchantSeesWhatWasUsedAtIt_whatItWasPaid_andWhatItIsStillOwed() throws Exception {
        Activity act = spendMoney();
        String period = "?from=" + LocalDate.now(ZoneOffset.UTC).minusDays(1) + "&to=" + LocalDate.now(ZoneOffset.UTC);

        JsonNode bolaStatement = superAdminGet("/loyalty/partner-vouchers/merchants/" + bola + "/statement" + period)
                .path("data");
        assertThat(bolaStatement.path("merchantName").asText()).isEqualTo("Bola Bet");
        JsonNode bolaUsd = only(bolaStatement.path("currencies"));
        assertThat(bolaUsd.path("currency").asText()).isEqualTo("USD");
        assertAmount(bolaUsd.path("redeemedHere"), "7.00", 1);
        assertThat(bolaUsd.path("redeemedHereByIssuer")).hasSize(1);
        assertThat(bolaUsd.path("redeemedHereByIssuer").get(0).path("merchantId").asText()).isEqualTo(mwos.toString());
        assertThat(bolaUsd.path("redeemedHereByIssuer").get(0).path("merchantName").asText()).isEqualTo("MWOS");
        assertAmount(bolaUsd.path("settledToYou"), "0.00", 0);
        assertAmount(bolaUsd.path("owedToYouNow"), "7.00", 1);
        assertAmount(bolaUsd.path("sold"), "0.00", 0);
        assertAmount(bolaUsd.path("unspentNow"), "0.00", 0);
        assertThat(bolaStatement.path("owedUses")).hasSize(1);
        JsonNode owed = bolaStatement.path("owedUses").get(0);
        assertThat(owed.path("redemptionId").asText()).isEqualTo(act.usedAtBola().toString());
        assertThat(owed.path("issuerName").asText()).isEqualTo("MWOS");
        assertThat(owed.path("amount").decimalValue()).isEqualByComparingTo("7.00");
        assertThat(owed.path("settlementStatus").asText()).isEqualTo("PENDING");
        assertThat(bolaStatement.path("owedUsesTruncated").asBoolean()).isFalse();

        JsonNode mwosStatement = superAdminGet("/loyalty/partner-vouchers/merchants/" + mwos + "/statement" + period)
                .path("data");
        JsonNode mwosUsd = only(mwosStatement.path("currencies"));
        // 5.00 of A, and the 15.00 Flow 2 code paid and spent at the agent.
        assertAmount(mwosUsd.path("redeemedHere"), "20.00", 2);
        assertAmount(mwosUsd.path("settledToYou"), "5.00", 1);
        assertAmount(mwosUsd.path("paidAtRedeemToYou"), "15.00", 1);
        assertAmount(mwosUsd.path("owedToYouNow"), "0.00", 0);
        assertAmount(mwosUsd.path("sold"), "30.00", 2);
        assertThat(mwosUsd.path("soldByMethod")).hasSize(1);
        assertThat(mwosUsd.path("soldByMethod").get(0).path("method").asText()).isEqualTo("CASH");
        JsonNode usedAt = mwosUsd.path("yourVouchersRedeemedAt");
        assertThat(usedAt).hasSize(2);
        assertThat(usedAt.get(0).path("merchantName").asText()).isEqualTo("Bola Bet");
        assertThat(usedAt.get(0).path("amount").decimalValue()).isEqualByComparingTo("7.00");
        assertThat(usedAt.get(1).path("merchantName").asText()).isEqualTo("MWOS");
        assertThat(usedAt.get(1).path("amount").decimalValue()).isEqualByComparingTo("5.00");
        assertAmount(mwosUsd.path("unspentNow"), "8.00", 1);
        assertAmount(mwosUsd.path("expiredUnreleasedNow"), "10.00", 1);
        assertThat(mwosStatement.path("owedUses")).isEmpty();

        // InnBucks' list has both merchants, by name.
        JsonNode platform = superAdminGet("/loyalty/partner-vouchers/statement" + period).path("data");
        JsonNode mwosLine = lineFor(platform, mwos);
        assertThat(mwosLine.path("merchantCodes").get(0).asText()).isEqualTo(mwosCode);
        JsonNode mwosPlatformUsd = only(mwosLine.path("currencies"));
        assertAmount(mwosPlatformUsd.path("sold"), "30.00", 2);
        assertAmount(mwosPlatformUsd.path("redeemedHere"), "20.00", 2);
        assertAmount(mwosPlatformUsd.path("owedNow"), "0.00", 0);
        assertAmount(mwosPlatformUsd.path("unspentNow"), "18.00", 2);
        JsonNode bolaPlatformUsd = only(lineFor(platform, bola).path("currencies"));
        assertAmount(bolaPlatformUsd.path("owedNow"), "7.00", 1);
        assertAmount(bolaPlatformUsd.path("redeemedHere"), "7.00", 1);
    }

    @Test
    void aMerchantAdminReadsItsOwnStatementOnly_andOnlyInnBucksRunsTheReconciliation() throws Exception {
        String own = TestJwtFactory.builder("admin@mwos.test").role("MERCHANT_ADMIN")
                .organization(mwosOrganization, "OWNER", List.of("loyalty")).sign(jwtSecret);
        assertThat(call(get("/loyalty/partner-vouchers/merchants/" + mwos + "/statement"), own).status())
                .isEqualTo(200);
        Call other = call(get("/loyalty/partner-vouchers/merchants/" + bola + "/statement"), own);
        assertThat(other.status()).isEqualTo(403);
        assertThat(other.code()).isEqualTo("NOT_MERCHANT_OWNER");
        Call notStaff = call(get("/loyalty/partner-vouchers/statement"), own);
        assertThat(notStaff.status()).isEqualTo(403);
        // The body the Swagger example (FORBIDDEN_403) shows.
        assertThat(notStaff.code()).isEqualTo("403 FORBIDDEN");
        assertThat(notStaff.body().path("message").asText()).isEqualTo("You don't have permission to do that.");
        assertThat(call(get("/loyalty/partner-vouchers/reconciliation"), own).status()).isEqualTo(403);
        assertThat(call(post("/loyalty/partner-vouchers/reconciliation/run"), own).status()).isEqualTo(403);

        // A cashier is not shown the business's money.
        String cashier = TestJwtFactory.shopAdmin("shop@mwos.test", mwos, UUID.randomUUID(), jwtSecret);
        assertThat(call(get("/loyalty/partner-vouchers/merchants/" + mwos + "/statement"), cashier).status())
                .isEqualTo(403);

        // Read-only platform access reads everything and runs nothing.
        String reader = TestJwtFactory.builder("dev@innbucks.co.zw").role("PRODUCT_OFFICER")
                .permissions(List.of(ReportsPermissions.READ)).sign(jwtSecret);
        assertThat(call(get("/loyalty/partner-vouchers/merchants/" + bola + "/statement"), reader).status())
                .isEqualTo(200);
        assertThat(call(get("/loyalty/partner-vouchers/statement"), reader).status()).isEqualTo(200);
        assertThat(call(get("/loyalty/partner-vouchers/reconciliation"), reader).status()).isEqualTo(200);
        assertThat(call(post("/loyalty/partner-vouchers/reconciliation/run"), reader).status()).isEqualTo(403);

        String superAdmin = TestJwtFactory.superAdmin(jwtSecret);
        Call inverted = call(get("/loyalty/partner-vouchers/merchants/" + mwos + "/statement?from=2026-11-10&to=2026-11-01"),
                superAdmin);
        assertThat(inverted.status()).isEqualTo(400);
        assertThat(inverted.code()).isEqualTo("RANGE_INVERTED");
        Call unknown = call(get("/loyalty/partner-vouchers/merchants/" + UUID.randomUUID() + "/statement"), superAdmin);
        assertThat(unknown.status()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- helpers

    private record Call(int status, JsonNode body) {
        String code() {
            return body.path("code").asText();
        }
    }

    private JsonNode runByHand() throws Exception {
        String token = TestJwtFactory.builder("owner@test.local").role("SUPER_ADMIN").sign(jwtSecret);
        Call c = call(post("/loyalty/partner-vouchers/reconciliation/run"), token);
        assertThat(c.status()).as(c.body().toString()).isEqualTo(200);
        return c.body().path("data");
    }

    private static JsonNode usd(JsonNode run) {
        for (JsonNode line : run.path("currencies")) {
            if ("USD".equals(line.path("currency").asText())) {
                return line;
            }
        }
        throw new AssertionError("No USD line in " + run);
    }

    private static BigDecimal moved(JsonNode before, JsonNode after, String field) {
        return after.path(field).decimalValue().subtract(before.path(field).decimalValue());
    }

    private double counter(String outcome) {
        return meters.get("loyalty.partner.reconciliation.runs").tag("outcome", outcome).counter().count();
    }

    private static JsonNode only(JsonNode array) {
        assertThat(array).hasSize(1);
        return array.get(0);
    }

    private static void assertAmount(JsonNode node, String amount, long count) {
        assertThat(node.path("amount").decimalValue()).as(node.toString()).isEqualByComparingTo(amount);
        assertThat(node.path("count").asLong()).as(node.toString()).isEqualTo(count);
    }

    private static JsonNode lineFor(JsonNode platform, UUID merchantId) {
        for (JsonNode m : platform.path("merchants")) {
            if (merchantId.toString().equals(m.path("merchantId").asText())) {
                return m;
            }
        }
        throw new AssertionError("No line for " + merchantId);
    }

    private UUID merchant(UUID tenantId, String name) {
        return MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest(name, "Betting", "USD", Merchant.BillingCycle.DAILY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
    }

    private void link(String partnerId, UUID merchantId, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId.toString());
        body.put("merchantCode", code);
        superAdmin(post("/loyalty/admin/voucher-partners/" + partnerId + "/merchants"), null, body, 200);
    }

    /** A cash sale into holding, usable at every partner, spent in parts. */
    private Map<String, Object> sale(String reference, String phone, String amount) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("merchant", mwosCode);
        s.put("partnerReference", reference);
        s.put("channel", "POS");
        s.put("terminalId", "T-1");
        s.put("amount", new BigDecimal(amount));
        s.put("currency", "USD");
        s.put("customer", Map.of("phone", phone, "name", "Tawanda Mpofu"));
        s.put("funding", Map.of("method", "CASH"));
        s.put("usage", "MULTIPLE");
        s.put("redeemableAt", "ANY");
        return s;
    }

    /** A Flow 2 code: no payment at generate. */
    private Map<String, Object> unpaid(String reference, String phone, String amount) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("merchant", mwosCode);
        s.put("partnerReference", reference);
        s.put("amount", new BigDecimal(amount));
        s.put("currency", "USD");
        s.put("customer", Map.of("phone", phone, "name", "Tawanda Mpofu"));
        return s;
    }

    private Map<String, Object> redeem(String merchantCode, String code, String amount, String reference,
                                       Map<String, Object> funding) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("merchant", merchantCode);
        r.put("code", code);
        r.put("amount", new BigDecimal(amount));
        r.put("currency", "USD");
        r.put("reference", reference);
        r.put("endUserRef", "ACC-1");
        r.put("channel", "ONLINE");
        if (funding != null) {
            r.put("funding", funding);
            r.put("terminalId", "AGT-T-1");
        }
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

    private Call call(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult r = mockMvc.perform(request.header("Authorization", bearer(token))).andReturn();
        String body = r.getResponse().getContentAsString();
        return new Call(r.getResponse().getStatus(), body.isBlank() ? json.nullNode() : json.readTree(body));
    }

    private JsonNode superAdminGet(String path) throws Exception {
        Call c = call(get(path), TestJwtFactory.builder("owner@test.local").role("SUPER_ADMIN").sign(jwtSecret));
        assertThat(c.status()).as(c.body().toString()).isEqualTo(200);
        return c.body();
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
