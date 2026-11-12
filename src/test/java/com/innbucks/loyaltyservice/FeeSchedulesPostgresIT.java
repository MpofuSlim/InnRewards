package com.innbucks.loyaltyservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FeeSchedule;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.repository.FeeScheduleRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.service.InvoicingService;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.MerchantFixtures;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fees are InnBucks' to set (V67), end to end against real Postgres: the
 * table's constraints and append-only trigger, the latest-row-per-scope query,
 * the {@code /loyalty/fees} endpoints through a real JWT and the real filter
 * chain, the refusals on merchant and rule creation, and an invoice priced from
 * InnBucks' schedule rather than the fee the merchant set for itself.
 */
class FeeSchedulesPostgresIT extends ControllerSecurityTestBase {

    private static final UUID ORG = TestJwtFactory.DEFAULT_ORGANIZATION;
    private static final String FINANCE = "finance@innbucks.co.zw";

    @Autowired JdbcTemplate jdbc;
    @Autowired FeeScheduleRepository schedules;
    @Autowired MerchantRepository merchantRepository;
    @Autowired MerchantService merchantService;
    @Autowired VoucherService voucherService;
    @Autowired InvoicingService invoicingService;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID tenantId;
    private UUID merchantId;

    @BeforeEach
    void aBrandWithOneMerchant() {
        Tenant t = new Tenant();
        t.setCode("fees-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Fees Brand");
        t.setOrganizationId(ORG);
        tenantId = tenantRepository.save(t).getId();
        // The merchant set its own fee the legacy way: FIXED 0.05 on its record.
        merchantId = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest("Fees Cafe " + UUID.randomUUID(), "F&B", "USD",
                        Merchant.BillingCycle.DAILY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null), null,
                        null, null, null, ORG)).id();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── tokens ───────────────────────────────────────────────────────────────

    private String superAdmin() {
        return bearer(TestJwtFactory.superAdmin(jwtSecret));
    }

    /** A finance role that is not SUPER_ADMIN but holds the code, as user-service mints it. */
    private String finance() {
        return bearer(TestJwtFactory.builder(FINANCE).role("PRODUCT_OFFICER").userId(UUID.randomUUID())
                .permissions(List.of("loyalty-fees:manage")).sign(jwtSecret));
    }

    private String reader() {
        return bearer(TestJwtFactory.builder("dev@innbucks.co.zw").role("PRODUCT_OFFICER")
                .permissions(List.of("loyalty-reports:read")).sign(jwtSecret));
    }

    private String merchantAdmin() {
        return bearer(TestJwtFactory.merchantAdmin("owner@feesbrand.co.zw", jwtSecret));
    }

    private static String schedule(String issuedType, String issuedFixed, String issuedPct,
                                   String redeemedType, String redeemedFixed, String redeemedPct) {
        return """
                {"feeIssued":{"type":"%s","fixed":%s,"percentage":%s},
                 "feeRedeemed":{"type":"%s","fixed":%s,"percentage":%s},
                 "note":"2026 rate card"}
                """.formatted(issuedType, issuedFixed, issuedPct, redeemedType, redeemedFixed, redeemedPct);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    // ── the table ────────────────────────────────────────────────────────────

    private int insert(boolean cleared, String issuedType, String issuedFixed, String issuedPct,
                       String redeemedType, String redeemedFixed, String redeemedPct) {
        return jdbc.update("""
                        INSERT INTO fee_schedules (id, tenant_id, merchant_id, cleared,
                            fee_issued_type, fee_issued_fixed, fee_issued_percentage,
                            fee_redeemed_type, fee_redeemed_fixed, fee_redeemed_percentage,
                            set_by, created_at)
                        VALUES (?, ?, NULL, ?, ?, ?::numeric, ?::numeric, ?, ?::numeric, ?::numeric, 'it', now())
                        """, UUID.randomUUID(), tenantId, cleared,
                issuedType, issuedFixed, issuedPct, redeemedType, redeemedFixed, redeemedPct);
    }

    @Test
    void theTableRefusesHalfASchedule_aBadValue_andAnyEdit() {
        assertThatThrownBy(() -> insert(false, "FIXED", "0.30", "0", null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_fee_schedule_shape");
        assertThatThrownBy(() -> insert(true, "FIXED", "0.30", "0", null, null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_fee_schedule_shape");
        assertThatThrownBy(() -> insert(false, "PERCENTAGE", "0", "100.5", "FIXED", "0", "0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_fee_schedule_values");
        assertThatThrownBy(() -> insert(false, "FIXED", "-0.01", "0", "FIXED", "0", "0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_fee_schedule_values");
        assertThatThrownBy(() -> insert(false, "FLAT", "0.30", "0", "FIXED", "0", "0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_fee_schedule_types");

        assertThat(insert(false, "FIXED", "0.30", "0", "PERCENTAGE", "0", "1.5")).isEqualTo(1);
        assertThat(insert(true, null, null, null, null, null, null)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("UPDATE fee_schedules SET note = 'x' WHERE tenant_id = ?", tenantId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM fee_schedules WHERE tenant_id = ?", tenantId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    private FeeSchedule row(UUID merchant, boolean cleared, String issuedFixed, Instant at) {
        FeeSchedule s = new FeeSchedule();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setMerchantId(merchant);
        s.setCleared(cleared);
        if (!cleared) {
            s.setFeeIssuedType(Merchant.FeeType.FIXED);
            s.setFeeIssuedFixed(new BigDecimal(issuedFixed));
            s.setFeeIssuedPercentage(BigDecimal.ZERO);
            s.setFeeRedeemedType(Merchant.FeeType.FIXED);
            s.setFeeRedeemedFixed(BigDecimal.ZERO);
            s.setFeeRedeemedPercentage(BigDecimal.ZERO);
        }
        s.setSetBy("it");
        s.setCreatedAt(at);
        return schedules.save(s);
    }

    @Test
    void theLatestRowOfEachScopeIsWhatTheResolverReads_tombstonesIncluded() {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(600);
        row(null, false, "0.10", t0);
        FeeSchedule tenantLatest = row(null, false, "0.20", t0.plusSeconds(60));
        row(merchantId, false, "0.30", t0.plusSeconds(30));
        FeeSchedule merchantLatest = row(merchantId, true, null, t0.plusSeconds(90));
        UUID otherTenant = newTenant("fees-other");
        jdbc.update("""
                INSERT INTO fee_schedules (id, tenant_id, cleared, fee_issued_type, fee_issued_fixed,
                    fee_issued_percentage, fee_redeemed_type, fee_redeemed_fixed, fee_redeemed_percentage,
                    set_by, created_at)
                VALUES (?, ?, FALSE, 'FIXED', 9, 0, 'FIXED', 9, 0, 'it', ?)
                """, UUID.randomUUID(), otherTenant, Timestamp.from(t0));

        List<FeeSchedule> latest = schedules.findLatestForTenants(List.of(tenantId));

        assertThat(latest).extracting(FeeSchedule::getId)
                .containsExactlyInAnyOrder(tenantLatest.getId(), merchantLatest.getId());
    }

    // ── the endpoints ────────────────────────────────────────────────────────

    @Test
    void innBucksSetsATenantDefaultAndAMerchantsOwn_andWithdrawsThem() throws Exception {
        mockMvc.perform(put("/loyalty/fees/tenants/{id}", tenantId).header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule("FIXED", "0.30", "0", "PERCENTAGE", "0", "1.5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.feeIssued.fixed").value(0.30))
                .andExpect(jsonPath("$.data.current.note").value("2026 rate card"))
                .andExpect(jsonPath("$.data.history.length()").value(1));

        // A finance role holding loyalty-fees:manage, not SUPER_ADMIN.
        mockMvc.perform(put("/loyalty/fees/merchants/{id}", merchantId).header("Authorization", finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule("FIXED", "0.10", "0", "FIXED", "0.05", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeIssuedSource").value("MERCHANT_SCHEDULE"))
                .andExpect(jsonPath("$.data.feeIssued.fixed").value(0.10))
                .andExpect(jsonPath("$.data.merchantSchedule.setBy").value(FINANCE))
                .andExpect(jsonPath("$.data.tenantSchedule.feeIssued.fixed").value(0.30));

        mockMvc.perform(delete("/loyalty/fees/merchants/{id}", merchantId).header("Authorization", finance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeIssuedSource").value("TENANT_SCHEDULE"))
                .andExpect(jsonPath("$.data.feeIssued.fixed").value(0.30))
                .andExpect(jsonPath("$.data.history.length()").value(2))
                .andExpect(jsonPath("$.data.history[0].cleared").value(true));
        mockMvc.perform(delete("/loyalty/fees/merchants/{id}", merchantId).header("Authorization", finance()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_FEE_SCHEDULE"));

        mockMvc.perform(delete("/loyalty/fees/tenants/{id}", tenantId).header("Authorization", superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").isEmpty());
        // Nothing live from InnBucks: back to the fee the merchant set for itself.
        mockMvc.perform(get("/loyalty/fees/merchants/{id}", merchantId).header("Authorization", superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeIssuedSource").value("MERCHANT_RECORD"))
                .andExpect(jsonPath("$.data.feeIssued.fixed").value(0.05));
    }

    @Test
    void aBadScheduleIsRefused_beforeAnythingIsWritten() throws Exception {
        mockMvc.perform(put("/loyalty/fees/tenants/{id}", tenantId).header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule("PERCENTAGE", "0", "101", "FIXED", "0", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FEE_PERCENT_TOO_HIGH"));
        mockMvc.perform(put("/loyalty/fees/tenants/{id}", tenantId).header("Authorization", superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feeIssued\":{\"type\":\"FIXED\",\"fixed\":0.3}}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fee_schedules WHERE tenant_id = ?",
                Long.class, tenantId)).isZero();
    }

    @Test
    void aMerchantAdmin_readsItsOwnFeesWithoutTheAuthor_andCannotSetAny() throws Exception {
        mockMvc.perform(put("/loyalty/fees/merchants/{id}", merchantId).header("Authorization", finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule("FIXED", "0.10", "0", "FIXED", "0", "0")))
                .andExpect(status().isOk());

        JsonNode own = body(mockMvc.perform(get("/loyalty/fees/merchants/{id}", merchantId)
                        .header("Authorization", merchantAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeIssuedSource").value("MERCHANT_SCHEDULE"))
                .andReturn());
        JsonNode setBy = own.at("/data/merchantSchedule/setBy");
        assertThat(setBy.isMissingNode() || setBy.isNull()).as("the merchant never learns who set it").isTrue();

        mockMvc.perform(put("/loyalty/fees/merchants/{id}", merchantId).header("Authorization", merchantAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule("FIXED", "0", "0", "FIXED", "0", "0")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/loyalty/fees/merchants/{id}", merchantId).header("Authorization", merchantAdmin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/loyalty/fees/tenants/{id}", tenantId).header("Authorization", merchantAdmin()))
                .andExpect(status().isForbidden());

        // Another business's merchant is refused.
        UUID rival = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest("Rival " + UUID.randomUUID(), null, "USD", null, null, null,
                        null, null, null, UUID.randomUUID())).id();
        mockMvc.perform(get("/loyalty/fees/merchants/{id}", rival).header("Authorization", merchantAdmin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MERCHANT_OWNER"));
    }

    @Test
    void aMerchantAdmin_cannotSendFeesWhenOnboardingAMerchantOrWritingARule() throws Exception {
        mockMvc.perform(post("/loyalty/merchants").header("Authorization", merchantAdmin())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Free Cafe\",\"feeIssued\":{\"type\":\"PERCENTAGE\",\"fixed\":0,"
                                + "\"percentage\":0.5}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FEES_PLATFORM_ONLY"));
        mockMvc.perform(post("/loyalty/rules").header("Authorization", merchantAdmin())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionType\":\"PURCHASE\",\"pointsPerUnit\":1,"
                                + "\"feeIssued\":{\"type\":\"FIXED\",\"fixed\":0,\"percentage\":0}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FEES_PLATFORM_ONLY"));

        // Without fees, both still work: the earn terms stay the merchant admin's.
        mockMvc.perform(post("/loyalty/merchants").header("Authorization", merchantAdmin())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Plain Cafe " + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/loyalty/rules").header("Authorization", merchantAdmin())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionType\":\"PURCHASE\",\"pointsPerUnit\":2}"))
                .andExpect(status().isCreated());
    }

    @Test
    void readOnlyPlatformAccess_readsFeesAndWhoSetThem_butSetsNothing() throws Exception {
        mockMvc.perform(put("/loyalty/fees/tenants/{id}", tenantId).header("Authorization", finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule("FIXED", "0.30", "0", "FIXED", "0", "0")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/loyalty/fees/tenants/{id}", tenantId).header("Authorization", reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.setBy").value(FINANCE));
        mockMvc.perform(get("/loyalty/fees/merchants/{id}", merchantId).header("Authorization", reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeIssuedSource").value("TENANT_SCHEDULE"));
        mockMvc.perform(put("/loyalty/fees/tenants/{id}", tenantId).header("Authorization", reader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule("FIXED", "0", "0", "FIXED", "0", "0")))
                .andExpect(status().isForbidden());
    }

    // ── the bill ─────────────────────────────────────────────────────────────

    @Test
    void theInvoiceIsPricedFromInnBucksSchedule_notTheFeeTheMerchantSetItself() throws Exception {
        mockMvc.perform(put("/loyalty/fees/merchants/{id}", merchantId).header("Authorization", finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(schedule("FIXED", "0.30", "0", "FIXED", "0", "0")))
                .andExpect(status().isOk());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "it-fixture", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        for (int i = 0; i < 2; i++) {
            voucherService.issue(tenantId, new Dtos.IssueVoucherRequest(merchantId, null,
                    new BigDecimal("20.00"), "USD", null, "+263771234567", null, null,
                    Voucher.DeliveryChannel.NONE, null));
        }
        SecurityContextHolder.clearContext();

        Merchant m = merchantRepository.findById(merchantId).orElseThrow();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Invoice invoice = invoicingService.generate(m, today, today).orElseThrow();

        // Two vouchers at InnBucks' FIXED 0.30, not the merchant's own 0.05.
        assertThat(invoice.getVouchersIssued()).isEqualTo(2);
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("0.60");
    }
}
