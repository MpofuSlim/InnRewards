package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherDetail;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherReport;
import com.innbucks.loyaltyservice.dto.VoucherReportFilters;
import com.innbucks.loyaltyservice.dto.VoucherReportFilters.PaymentMethod;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherBatch;
import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherPurchaseOrderRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.service.ReportingService;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The voucher report's optional filters against REAL Postgres: the criteria
 * subqueries (payment type), LIKE escaping and the filtered summary only prove
 * themselves on a database. Fixtures are synthetic (no real phones or names).
 */
class VoucherReportFiltersIT extends PostgresIntegrationTestBase {

    @Autowired TenantRepository tenants;
    @Autowired MerchantRepository merchants;
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherBatchRepository batches;
    @Autowired VoucherPurchaseOrderRepository orders;
    @Autowired ReportingService reporting;

    @MockitoBean UserServiceClient userServiceClient;

    private UUID tenantId;
    private UUID merchantA;
    private UUID merchantB;

    private Voucher free;       // bulk, no order
    private Voucher ecocash;    // paid online, rail known
    private Voucher online;     // paid online before rails were recorded
    private Voucher cash;       // paid at the till, ZAR
    private Voucher cardPos;    // card machine, other merchant

    /** Codes are unique across the whole table and every test re-seeds, so each
     *  run gets its own 8-digit prefix; the fixed 8-digit tails are what the
     *  search assertions look for. */
    private String prefix;
    private Instant expires;

    @BeforeEach
    void seed() {
        Tenant t = new Tenant();
        t.setCode("vrf-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Voucher Report Filters");
        tenantId = tenants.save(t).getId();
        prefix = String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L));
        expires = Instant.now().plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS);
        merchantA = merchant("Alpha Fuel");
        merchantB = merchant("Beta Fuel");

        free = voucher(merchantA, prefix + "33334444", "USD", "3.00", null, null, null, "issuer.one@example.test");
        VoucherBatch batch = new VoucherBatch();   // vouchers.batch_id is a real FK
        batch.setTenantId(tenantId);
        batch.setQuantity(1);
        batch.setCampaign("spring-test");
        free.setBatchId(batches.save(batch).getId());
        free.setCampaignSource("spring-test");
        vouchers.save(free);

        ecocash = vouchers.save(voucher(merchantA, prefix + "55556666", "USD", "10.00",
                "Test Recipient", "+263770000101", "+263770000202", "issuer.two@example.test"));
        paidOrder(ecocash, VoucherPurchaseOrder.PaidVia.GATEWAY, "ECOCASH");

        online = vouchers.save(voucher(merchantA, prefix + "11112222", "USD", "5.00",
                "Other Person", "+263770000303", null, "issuer.two@example.test"));
        paidOrder(online, VoucherPurchaseOrder.PaidVia.GATEWAY, null);

        cash = vouchers.save(voucher(merchantA, prefix + "90901212", "ZAR", "50.00",
                "Cash Customer", "+263770000404", "0770000202", "issuer.one@example.test"));
        paidOrder(cash, VoucherPurchaseOrder.PaidVia.CASH, null);

        cardPos = vouchers.save(voucher(merchantB, prefix + "99990000", "USD", "7.00",
                "Card Customer", "+263770000505", null, "issuer.three@example.test"));
        paidOrder(cardPos, VoucherPurchaseOrder.PaidVia.CARD_POS, null);
    }

    private UUID merchant(String name) {
        Merchant m = new Merchant();
        m.setTenantId(tenantId);
        m.setName(name);
        m.setCurrency("USD");
        return merchants.save(m).getId();
    }

    private Voucher voucher(UUID merchantId, String code, String currency, String value,
                            String recipientName, String recipientPhone, String senderPhone, String issuerEmail) {
        Voucher v = new Voucher();
        v.setTenantId(tenantId);
        v.setMerchantId(merchantId);
        v.setCode(code);
        v.setSignature("sig-" + code);
        v.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        v.setValue(new BigDecimal(value));
        v.setCurrency(currency);
        v.setBaseValue(new BigDecimal(value));
        v.setAssigneeName(recipientName);
        v.setAssigneePhone(recipientPhone);
        v.setSenderPhone(senderPhone);
        v.setIssuerEmail(issuerEmail);
        v.setIssuedAt(Instant.now());
        v.setExpiresAt(expires);
        return v;
    }

    private void paidOrder(Voucher v, VoucherPurchaseOrder.PaidVia via, String rail) {
        VoucherPurchaseOrder o = new VoucherPurchaseOrder();
        o.setOrderRef("VCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        o.setTenantId(tenantId);
        o.setMerchantId(v.getMerchantId());
        o.setStatus(VoucherPurchaseOrder.Status.PAID);
        o.setAmount(v.getValue());
        o.setCurrency(v.getCurrency());
        o.setPayerPhone("+263770000999");
        o.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        o.setUsageLimit(1);
        o.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
        o.setVoucherId(v.getId());
        o.setPaidVia(via);
        o.setPaymentRef(via == VoucherPurchaseOrder.PaidVia.GATEWAY ? "TKZ-" + v.getCode() : null);
        o.setPaymentRail(rail);
        o.setPaidAt(Instant.now());
        if (via == VoucherPurchaseOrder.PaidVia.CASH) o.setCashConfirmedBy("issuer.one@example.test");
        if (via == VoucherPurchaseOrder.PaidVia.CARD_POS) {
            o.setCashConfirmedBy("issuer.three@example.test");
            o.setCardApprovalCode("A1B2C3");
        }
        orders.save(o);
    }

    private static VoucherReportFilters.Builder filters() {
        return new VoucherReportFilters.Builder();
    }

    private List<UUID> ids(VoucherReportFilters f) {
        VoucherReport r = reporting.vouchersForTenant(tenantId, null, null, null, null, f,
                PageRequest.of(0, 50));
        return r.vouchers().getContent().stream().map(VoucherDetail::id).toList();
    }

    @Test
    void everyRowCarriesItsPaymentMethod_andOrderRef() {
        VoucherReport r = reporting.vouchersForTenant(tenantId, null, null, null, null,
                VoucherReportFilters.none(), PageRequest.of(0, 50));
        var byId = r.vouchers().getContent().stream()
                .collect(java.util.stream.Collectors.toMap(VoucherDetail::id, d -> d));

        assertThat(byId.get(free.getId()).paymentMethod()).isEqualTo("FREE");
        assertThat(byId.get(free.getId()).orderRef()).isNull();
        assertThat(byId.get(ecocash.getId()).paymentMethod()).isEqualTo("ECOCASH");
        assertThat(byId.get(ecocash.getId()).orderRef()).startsWith("VCH-");
        assertThat(byId.get(online.getId()).paymentMethod()).isEqualTo("ONLINE");
        assertThat(byId.get(cash.getId()).paymentMethod()).isEqualTo("CASH");
        assertThat(byId.get(cardPos.getId()).paymentMethod()).isEqualTo("CARD_POS");
    }

    @Test
    void paymentMethod_filtersByHowTheVoucherWasPaid() {
        assertThat(ids(filters().paymentMethod(PaymentMethod.FREE).build())).containsExactly(free.getId());
        assertThat(ids(filters().paymentMethod(PaymentMethod.ECOCASH).build())).containsExactly(ecocash.getId());
        assertThat(ids(filters().paymentMethod(PaymentMethod.ONLINE).build()))
                .containsExactlyInAnyOrder(ecocash.getId(), online.getId());
        assertThat(ids(filters().paymentMethod(PaymentMethod.CASH).build())).containsExactly(cash.getId());
        assertThat(ids(filters().paymentMethod(PaymentMethod.CARD_POS).build())).containsExactly(cardPos.getId());
        assertThat(ids(filters().paymentMethod(PaymentMethod.INNBUCKS).build())).isEmpty();
    }

    @Test
    void currency_merchant_andValueRange() {
        assertThat(ids(filters().currency("zar").build())).containsExactly(cash.getId());
        assertThat(ids(filters().merchantId(merchantB).build())).containsExactly(cardPos.getId());
        assertThat(ids(filters().minValue(new BigDecimal("6")).maxValue(new BigDecimal("20")).build()))
                .containsExactlyInAnyOrder(ecocash.getId(), cardPos.getId());
    }

    @Test
    void phone_matchesRecipientOrSender_inAnySpelling() {
        // Stored "+263770000202" as a sender AND "0770000202" (a pre-V56 raw sender).
        assertThat(ids(filters().phone("0770000202").build()))
                .containsExactlyInAnyOrder(ecocash.getId(), cash.getId());
        assertThat(ids(filters().phone("+263 77 000 0404").build())).containsExactly(cash.getId());
    }

    @Test
    void search_matchesNameCodeAndIssuer_andTreatsWildcardsLiterally() {
        assertThat(ids(filters().q("test recip").build())).containsExactly(ecocash.getId());
        assertThat(ids(filters().q("5555-6666").build())).containsExactly(ecocash.getId());
        assertThat(ids(filters().q(ecocash.getCode()).build())).containsExactly(ecocash.getId());
        assertThat(ids(filters().q("issuer.three").build())).containsExactly(cardPos.getId());
        assertThat(ids(filters().q("%").build())).isEmpty();
    }

    @Test
    void issuedBy_bulk_batch_andCampaign() {
        assertThat(ids(filters().issuedBy("ISSUER.ONE").build()))
                .containsExactlyInAnyOrder(free.getId(), cash.getId());
        assertThat(ids(filters().bulk(true).build())).containsExactly(free.getId());
        assertThat(ids(filters().bulk(false).build())).doesNotContain(free.getId()).hasSize(4);
        assertThat(ids(filters().batchId(free.getBatchId()).build())).containsExactly(free.getId());
        assertThat(ids(filters().campaign("spring-test").build())).containsExactly(free.getId());
    }

    @Test
    void expiryAndRedemptionWindows() {
        LocalDate expiry = free.getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate();
        assertThat(ids(filters().expiresFrom(expiry).expiresTo(expiry).build())).hasSize(5);
        assertThat(ids(filters().expiresTo(expiry.minusDays(2)).build())).isEmpty();
        assertThat(ids(filters().redeemedFrom(LocalDate.now(ZoneOffset.UTC).minusDays(1)).build())).isEmpty();
    }

    @Test
    void theSummaryHonoursTheFilters_butNotTheStatus() {
        VoucherReport r = reporting.vouchersForTenant(tenantId, null, Voucher.Status.REDEEMED, null, null,
                filters().paymentMethod(PaymentMethod.ONLINE).build(), PageRequest.of(0, 50));

        assertThat(r.vouchers().getContent()).isEmpty();          // none of them is redeemed
        assertThat(r.summary().totalIssued()).isEqualTo(2);        // but the ONLINE tabs still count
        assertThat(r.summary().countByStatus()).containsEntry("ISSUED", 2L);
    }

    @Test
    void theCsvExportTakesTheSameFilters() {
        String csv = reporting.voucherCsv("TENANT", tenantId, null, null, null, null, null,
                filters().paymentMethod(PaymentMethod.CASH).build());

        assertThat(csv.lines().toList()).hasSize(2);
        assertThat(csv.lines().findFirst().orElseThrow()).endsWith(",paymentMethod,orderRef");
        assertThat(csv).contains(",CASH,VCH-");
    }
}
