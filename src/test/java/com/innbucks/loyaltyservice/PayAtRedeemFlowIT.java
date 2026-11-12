package com.innbucks.loyaltyservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import com.innbucks.loyaltyservice.entity.VoucherRedeemPayment;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.VoucherPurchaseOrderRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedeemPaymentRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Flow 2 (V69) end to end: a voucher generated with no payment, paid in full at
 * the issuer's agent through the redeem, through real signed requests, the real
 * filter, controller, services and Postgres, with SIMULATED funding. The rule
 * that matters most: there is no voucher, and so nothing that could ever be paid
 * out of holding, until the agent's payment has confirmed.
 */
@TestPropertySource(properties = {
        "loyalty.voucher-partner.enabled=true",
        "loyalty.voucher-partner.audience=" + PayAtRedeemFlowIT.AUDIENCE,
        "loyalty.voucher-partner.funding=SIMULATED",
        "loyalty.voucher-partner.simulated-delay=PT0S",
        // The test drives every payment itself; the background job must not race it.
        "loyalty.voucher-partner.reconcile-interval=PT1H"
})
class PayAtRedeemFlowIT extends ControllerSecurityTestBase {

    static final String AUDIENCE = "innbucks-loyalty-vouchers:pay-at-redeem-it";
    private static final String CALLER_IP = "203.0.113.11";

    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;
    @MockitoBean PartnerJtiStore jtiStore;

    @Autowired MerchantService merchantService;
    @Autowired VoucherRepository voucherRepository;
    @Autowired VoucherRedemptionRepository redemptionRepository;
    @Autowired VoucherPurchaseOrderRepository orderRepository;
    @Autowired VoucherRedeemPaymentRepository paymentRepository;
    @Autowired ObjectMapper json;

    private final KeyPair keys = PartnerJws.ecKeyPair();
    private UUID merchantId;
    private String issuer;
    private String kid;

    @BeforeEach
    void setUp() throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        when(jtiStore.firstUse(anyString(), any())).thenAnswer(inv -> seen.add(inv.getArgument(0)));

        UUID tenantId = newTenant("mwos");
        merchantId = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest("MWOS " + UUID.randomUUID(), "Betting", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        issuer = "innbucks-middleware-" + UUID.randomUUID();
        kid = "mw-par-" + UUID.randomUUID();
        JsonNode created = admin(post("/loyalty/admin/voucher-partners"), Map.of(
                "name", "InnBucks middleware", "issuer", issuer, "allowedIps", List.of(CALLER_IP)), 201);
        String partnerId = created.path("data").path("partnerId").asText();
        admin(post("/loyalty/admin/voucher-partners/" + partnerId + "/keys"), Map.of(
                "kid", kid, "alg", "ES256", "publicKeyPem", PartnerJws.pem(keys.getPublic())), 200);
        admin(post("/loyalty/admin/voucher-partners/" + partnerId + "/merchants"), Map.of(
                "merchantId", merchantId.toString(), "merchantCode", "MWOS"), 200);
    }

    @Test
    void aCodeIsIssuedWithNoMoney_paidInFullAtTheAgent_andOnlyThenBecomesAVoucher_spentAtOnce() throws Exception {
        String phone = newPhone();
        String saleRef = "MWOS-APP-" + UUID.randomUUID();
        Call issued = partner("generate", unpaidSale(saleRef, phone, "10.00"));
        assertThat(issued.status()).as(issued.body().toString()).isEqualTo(201);
        JsonNode data = issued.body().path("data");
        assertThat(data.path("status").asText()).isEqualTo("ISSUED");
        assertThat(data.path("channel").asText()).isEqualTo("ONLINE");
        assertThat(data.path("funding").path("status").asText()).isEqualTo("AT_REDEEM");
        assertThat(data.path("funding").path("method").isNull()).isTrue();
        JsonNode view = data.path("voucher");
        assertThat(view.path("voucherId").isNull()).isTrue();
        assertThat(view.path("status").asText()).isEqualTo("ISSUED");
        assertThat(view.path("usage").asText()).isEqualTo("SINGLE");
        assertThat(view.path("payAtRedeem").asBoolean()).isTrue();
        assertThat(view.path("redeemableAt")).hasSize(1);
        String code = view.path("code").asText();
        assertThat(code).matches("[0-9]{16}");

        // No voucher exists: nothing is held, counted, billed or warned about.
        assertThat(voucherRepository.findByCode(code)).isEmpty();
        verify(notificationGateway).notifyPayAtRedeemCode(argThat(c -> code.equals(c.code())
                && phone.equals(c.phone()) && c.amount().compareTo(new BigDecimal("10.00")) == 0));

        Call balance = partner("inquire", inquireCode(code));
        assertThat(balance.status()).isEqualTo(200);
        assertThat(balance.body().path("data").path("payAtRedeem").asBoolean()).isTrue();
        assertThat(balance.body().path("data").path("status").asText()).isEqualTo("ISSUED");
        assertThat(balance.body().path("data").path("redeemable").asBoolean()).isTrue();

        // The agent must take the payment, in full.
        Call noPayment = partner("redeem", redeem(code, null, "AGT-" + UUID.randomUUID(), null, "POS"));
        assertThat(noPayment.status()).isEqualTo(400);
        assertThat(noPayment.code()).isEqualTo("PAYMENT_REQUIRED");
        Call part = partner("redeem", redeem(code, "5.00", "AGT-" + UUID.randomUUID(), cash(), "POS"));
        assertThat(part.code()).isEqualTo("PARTIAL_USE_NOT_ALLOWED");
        Call cashOnline = partner("redeem", redeem(code, null, "AGT-" + UUID.randomUUID(), cash(), "ONLINE"));
        assertThat(cashOnline.code()).isEqualTo("FUNDING_METHOD_NOT_ALLOWED");
        assertThat(voucherRepository.findByCode(code)).isEmpty();

        String payRef = "AGT-" + UUID.randomUUID();
        Call paid = partner("redeem", redeem(code, "10.00", payRef, cash(), "POS"));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        JsonNode result = paid.body().path("data");
        assertThat(result.path("status").asText()).isEqualTo("REDEEMED");
        assertThat(result.path("value").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(result.path("remainingBalance").decimalValue()).isEqualByComparingTo("0");
        assertThat(result.path("funding").path("method").asText()).isEqualTo("CASH");
        assertThat(result.path("funding").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(result.path("settlement").path("status").asText()).isEqualTo("SETTLED");
        assertThat(result.path("settlement").path("transferReference").asText()).startsWith("SIM-PAY-");

        // Now, and only now, the voucher exists: spent, and already paid to the issuer.
        Voucher v = voucherRepository.findByCode(code).orElseThrow();
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.REDEEMED);
        assertThat(v.getRemainingBalance()).isEqualByComparingTo("0");
        assertThat(v.getValue()).isEqualByComparingTo("10.00");
        assertThat(v.getMerchantId()).isEqualTo(merchantId);
        assertThat(v.getPartnerId()).isNotNull();
        VoucherRedemption r = redemptionRepository.findById(UUID.fromString(result.path("redemptionId").asText()))
                .orElseThrow();
        assertThat(r.getResult()).isEqualTo(VoucherRedemption.Result.SUCCESS);
        assertThat(r.getMerchantId()).isEqualTo(merchantId);
        assertThat(r.isFirstUse()).isTrue();
        assertThat(r.getSettlementStatus()).isEqualTo(VoucherRedemption.SettlementStatus.SETTLED);
        VoucherPurchaseOrder order = orderRepository.findByReservedCode(code).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(VoucherPurchaseOrder.Status.PAID);
        assertThat(order.getPaidVia()).isEqualTo(VoucherPurchaseOrder.PaidVia.CASH);
        assertThat(order.getVoucherId()).isEqualTo(v.getId());
        verify(notificationGateway).notifyPaidAtRedeem(argThat(p -> phone.equals(p.phone())));

        // The sale's own inquiry now shows it paid.
        Call sale = partner("inquire", Map.of("merchant", "MWOS", "by", "PARTNER_REFERENCE",
                "partnerReference", saleRef));
        assertThat(sale.body().path("data").path("voucher").path("status").asText()).isEqualTo("REDEEMED");
        assertThat(sale.body().path("data").path("voucher").path("payAtRedeem").asBoolean()).isFalse();
        assertThat(sale.body().path("data").path("funding").path("status").asText()).isEqualTo("CONFIRMED");

        // The same redeem again replays it; any other is refused, payment or not.
        Call replay = partner("redeem", redeem(code, "10.00", payRef, cash(), "POS"));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("data").path("replayed").asBoolean()).isTrue();
        assertThat(replay.body().path("data").path("redemptionId").asText()).isEqualTo(r.getId().toString());
        Call payAgain = partner("redeem", redeem(code, null, "AGT-" + UUID.randomUUID(), cash(), "POS"));
        assertThat(payAgain.status()).isEqualTo(409);
        assertThat(payAgain.code()).isEqualTo("ALREADY_REDEEMED");
        Call useAgain = partner("redeem", redeem(code, null, "AGT-" + UUID.randomUUID(), null, "POS"));
        assertThat(useAgain.code()).isEqualTo("ALREADY_REDEEMED");
        Call byRedemption = partner("inquire", Map.of("merchant", "MWOS", "by", "REDEMPTION",
                "reference", payRef));
        assertThat(byRedemption.body().path("data").path("funding").path("status").asText()).isEqualTo("CONFIRMED");
        // One use; the refused attempts above are REJECTED audit rows, as for any voucher.
        assertThat(redemptionRepository.findAll().stream()
                .filter(x -> x.getVoucherId().equals(v.getId()))
                .filter(x -> x.getResult() == VoucherRedemption.Result.SUCCESS).count()).isEqualTo(1);
    }

    @Test
    void aRefusedPayment_movesNothing_andTheCodeCanBePaidAgainUnderANewReference() throws Exception {
        String phone = newPhone();
        String code = issue(phone, "8.00");

        Call declined = partner("redeem", redeem(code, null, "SIMFAIL-" + UUID.randomUUID(), cash(), "POS"));
        assertThat(declined.status()).isEqualTo(422);
        assertThat(declined.code()).isEqualTo("FLOAT_INSUFFICIENT");
        assertThat(declined.body().path("data").path("status").asText()).isEqualTo("FAILED");
        assertThat(declined.body().path("data").path("funding").path("failureCode").asText())
                .isEqualTo("FLOAT_INSUFFICIENT");
        Call unreachable = partner("redeem", redeem(code, null, "SIMDOWN-" + UUID.randomUUID(), cash(), "POS"));
        assertThat(unreachable.status()).isEqualTo(503);
        assertThat(unreachable.code()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(voucherRepository.findByCode(code)).isEmpty();
        assertThat(partner("inquire", inquireCode(code)).body().path("data").path("status").asText())
                .isEqualTo("ISSUED");
        verify(notificationGateway, never()).notifyPaidAtRedeem(any());

        Call paid = partner("redeem", redeem(code, null, "AGT-" + UUID.randomUUID(), cash(), "POS"));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        assertThat(voucherRepository.findByCode(code).orElseThrow().getStatus()).isEqualTo(Voucher.Status.REDEEMED);
    }

    @Test
    void aSlowPayment_isPending_blocksASecondAgent_andIsFinishedByAskingTheProvider() throws Exception {
        String code = issue(newPhone(), "12.00");
        String slowRef = "SIMSLOW-" + UUID.randomUUID();
        Call pending = partner("redeem", redeem(code, null, slowRef, cash(), "POS"));
        assertThat(pending.status()).as(pending.body().toString()).isEqualTo(202);
        assertThat(pending.body().path("data").path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.body().path("data").path("redemptionId").isNull()).isTrue();
        assertThat(voucherRepository.findByCode(code)).isEmpty();
        assertThat(paymentRepository.findOpenIds(PageRequest.of(0, 1000))).isNotEmpty();

        Call second = partner("redeem", redeem(code, null, "AGT-" + UUID.randomUUID(), cash(), "POS"));
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("PAYMENT_IN_PROGRESS");

        // An inquiry asks the provider (a read) and records the voucher once it confirmed.
        Call asked = partner("inquire", Map.of("merchant", "MWOS", "by", "REDEMPTION", "reference", slowRef));
        assertThat(asked.status()).isEqualTo(200);
        assertThat(asked.body().path("data").path("status").asText()).isEqualTo("REDEEMED");
        VoucherRedeemPayment p = paymentRepository.findByMerchantIdAndReference(merchantId, slowRef).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(VoucherRedeemPayment.Status.CONFIRMED);
        assertThat(p.getRedemptionId()).isNotNull();
        assertThat(voucherRepository.findByCode(code).orElseThrow().getStatus()).isEqualTo(Voucher.Status.REDEEMED);
    }

    @Test
    void agentsRacingToPayOneCode_payItOnce() throws Exception {
        String code = issue(newPhone(), "9.00");
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Call>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String ref = "AGT-RACE-" + i + "-" + UUID.randomUUID();
            // Each agent is its own account on the partner's platform (its own lockout budget).
            Map<String, Object> body = redeem(code, null, ref, cash(), "POS");
            body.put("endUserRef", "MWOS-AGENT-RACE-" + i);
            Callable<Call> task = () -> {
                start.await();
                return partner("redeem", body);
            };
            calls.add(pool.submit(task));
        }
        start.countDown();
        int ok = 0;
        for (Future<Call> f : calls) {
            Call c = f.get();
            if (c.status() == 200) {
                ok++;
            } else {
                assertThat(c.status()).as(c.body().toString()).isEqualTo(409);
                assertThat(c.code()).isIn("PAYMENT_IN_PROGRESS", "ALREADY_REDEEMED");
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        UUID voucherId = voucherRepository.findByCode(code).orElseThrow().getId();
        assertThat(redemptionRepository.findAll().stream().filter(r -> r.getVoucherId().equals(voucherId))
                .filter(r -> r.getResult() == VoucherRedemption.Result.SUCCESS).count()).isEqualTo(1);
    }

    @Test
    void theRulesOfACodePaidAtTheRedeem_andOfEveryOtherVoucher() throws Exception {
        String phone = newPhone();
        Map<String, Object> multiple = unpaidSale("R-" + UUID.randomUUID(), phone, "5.00");
        multiple.put("usage", "MULTIPLE");
        assertThat(partner("generate", multiple).code()).isEqualTo("USAGE_NOT_ALLOWED");
        Map<String, Object> anywhere = unpaidSale("R-" + UUID.randomUUID(), phone, "5.00");
        anywhere.put("redeemableAt", "ANY");
        assertThat(partner("generate", anywhere).code()).isEqualTo("REDEEMABLE_AT_NOT_ALLOWED");

        // A funded generate still needs its channel; an InnBucks code is never a POS's.
        Map<String, Object> noChannel = paidSale("R-" + UUID.randomUUID(), null, phone, "5.00", Map.of("method", "CASH"));
        assertThat(partner("generate", noChannel).body().path("data").path("channel").asText()).isEqualTo("is required");
        assertThat(partner("generate", paidSale("R-" + UUID.randomUUID(), "POS", phone, "5.00",
                Map.of("method", "INNBUCKS_CODE"))).code()).isEqualTo("FUNDING_METHOD_NOT_ALLOWED");
        assertThat(partner("generate", paidSale("R-" + UUID.randomUUID(), "ONLINE", phone, "5.00",
                Map.of("method", "MERCHANT_COLLECTED"))).code()).isEqualTo("COLLECTION_DETAILS_REQUIRED");

        // A voucher paid when it was sold never takes a second payment.
        Call sold = partner("generate", paidSale("R-" + UUID.randomUUID(), "POS", phone, "5.00",
                Map.of("method", "CASH")));
        assertThat(sold.status()).isEqualTo(201);
        assertThat(sold.body().path("data").path("voucher").path("payAtRedeem").asBoolean()).isFalse();
        String paidCode = sold.body().path("data").path("voucher").path("code").asText();
        assertThat(partner("redeem", redeem(paidCode, null, "AGT-" + UUID.randomUUID(), cash(), "POS")).code())
                .isEqualTo("PAYMENT_NOT_NEEDED");

        // A payment's reference is its own: another code, or other content, is refused.
        String code = issue(phone, "6.00");
        String payRef = "AGT-" + UUID.randomUUID();
        assertThat(partner("redeem", redeem(code, null, payRef, cash(), "POS")).status()).isEqualTo(200);
        String other = issue(newPhone(), "6.00");
        Call reused = partner("redeem", redeem(other, null, payRef, cash(), "POS"));
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("DUPLICATE_REFERENCE");
        Map<String, Object> changed = redeem(code, null, payRef, Map.of("method", "CARD_SWIPE",
                "approvalCode", "A1B2C3"), "POS");
        assertThat(partner("redeem", changed).code()).isEqualTo("IDEMPOTENCY_MISMATCH");

        // An expired code can't be paid, and says so.
        String late = issue(newPhone(), "7.00");
        VoucherPurchaseOrder o = orderRepository.findByReservedCode(late).orElseThrow();
        o.setExpiresAt(Instant.now().minusSeconds(60));
        orderRepository.saveAndFlush(o);
        assertThat(partner("inquire", inquireCode(late)).body().path("data").path("status").asText())
                .isEqualTo("EXPIRED");
        Call expired = partner("redeem", redeem(late, null, "AGT-" + UUID.randomUUID(), cash(), "POS"));
        assertThat(expired.status()).isEqualTo(400);
        assertThat(expired.code()).isEqualTo("EXPIRED");
    }

    // --------------------------------------------------------------- helpers

    private record Call(int status, JsonNode body) {
        String code() {
            return body.path("code").asText();
        }
    }

    /** A generate with no funding and no channel: the customer pays at the redeem. */
    private Map<String, Object> unpaidSale(String reference, String phone, String amount) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("merchant", "MWOS");
        s.put("partnerReference", reference);
        s.put("amount", new BigDecimal(amount));
        s.put("currency", "USD");
        s.put("customer", Map.of("phone", phone, "name", "Tawanda Mpofu"));
        return s;
    }

    private Map<String, Object> paidSale(String reference, String channel, String phone, String amount,
                                         Map<String, Object> funding) {
        Map<String, Object> s = unpaidSale(reference, phone, amount);
        if (channel != null) {
            s.put("channel", channel);
        }
        s.put("funding", funding);
        return s;
    }

    private String issue(String phone, String amount) throws Exception {
        Call issued = partner("generate", unpaidSale("MWOS-APP-" + UUID.randomUUID(), phone, amount));
        assertThat(issued.status()).as(issued.body().toString()).isEqualTo(201);
        return issued.body().path("data").path("voucher").path("code").asText();
    }

    private static Map<String, Object> cash() {
        return Map.of("method", "CASH", "accountNumber", "1001234567890");
    }

    private Map<String, Object> redeem(String code, String amount, String reference, Map<String, Object> funding,
                                       String channel) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("merchant", "MWOS");
        r.put("code", code);
        if (amount != null) {
            r.put("amount", new BigDecimal(amount));
        }
        r.put("currency", "USD");
        r.put("reference", reference);
        r.put("endUserRef", "MWOS-AGENT-7");
        r.put("channel", channel);
        r.put("terminalId", "AGT-T-1");
        if (funding != null) {
            r.put("funding", funding);
        }
        return r;
    }

    private Map<String, Object> inquireCode(String code) {
        return Map.of("merchant", "MWOS", "by", "CODE", "code", code, "endUserRef", "MWOS-AGENT-7");
    }

    private String sign(String op, Object body) {
        return PartnerJws.sign(PartnerJws.header("ES256", kid), PartnerJws.claims(issuer, AUDIENCE, op, body),
                keys.getPrivate());
    }

    private Call partner(String op, Object body) throws Exception {
        MvcResult r = mockMvc.perform(post("/loyalty/partner/vouchers/" + op)
                .header("X-Real-IP", CALLER_IP)
                .contentType("application/jose")
                .content(sign(op, body))).andReturn();
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
