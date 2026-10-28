package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.ReportsPermissions;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import com.innbucks.loyaltyservice.util.VoucherCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Read-only platform access ({@code loyalty-reports:read}) end to end: a real
 * minted JWT through the real filter chain, {@code TenantContext},
 * {@code MerchantAuthz}, controllers and services.
 *
 * <p>The token is the developer account's shape: roles {@code [PRODUCT_OFFICER]},
 * {@code perms: [loyalty-reports:read]}, no organization claims. Every tenant and
 * merchant here belongs to a business the reader has nothing to do with, and
 * the reader is a member of nothing — so a 200 is the permission working, never
 * a membership row.
 */
class ReadOnlyPlatformAccessSecurityTest extends ControllerSecurityTestBase {

    private static final UUID SOMEONE_ELSES_BUSINESS = UUID.fromString("5d6e7f80-1a2b-4c3d-8e9f-0a1b2c3d4e71");

    @Autowired MerchantRepository merchantRepository;
    @Autowired VoucherRepository voucherRepository;

    private UUID tenant;
    private UUID merchant;
    private Voucher voucher;

    @BeforeEach
    void seed() {
        Tenant t = new Tenant();
        t.setCode("RO-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Another business's program");
        t.setOrganizationId(SOMEONE_ELSES_BUSINESS);
        tenant = tenantRepository.save(t).getId();

        Merchant m = new Merchant();
        m.setTenantId(tenant);
        m.setName("Their merchant " + UUID.randomUUID());
        m.setOrganizationId(SOMEONE_ELSES_BUSINESS);
        merchant = merchantRepository.save(m).getId();

        Voucher v = new Voucher();
        v.setTenantId(tenant);
        v.setMerchantId(merchant);
        v.setCode("9" + String.format("%015d", ThreadLocalRandom.current().nextLong(1_000_000_000_000_000L)));
        v.setSignature("test-signature");
        v.setAssigneePhone("+263771234567");
        v.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        v.setValue(new BigDecimal("5.00"));
        v.setCurrency("USD");
        v.setStatus(Voucher.Status.ISSUED);
        v.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        voucher = voucherRepository.save(v);
    }

    private String reader() {
        return TestJwtFactory.builder("dev@innbucks.co.zw")
                .role("PRODUCT_OFFICER")
                .userId(UUID.randomUUID())
                .permissions(List.of(ReportsPermissions.READ))
                .sign(jwtSecret);
    }

    /** The same developer account without the permission. */
    private String productOfficerAlone() {
        return TestJwtFactory.builder("dev@innbucks.co.zw")
                .role("PRODUCT_OFFICER")
                .userId(UUID.randomUUID())
                .sign(jwtSecret);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String token) {
        return req.header("Authorization", bearer(token)).header("X-Tenant-Id", tenant.toString());
    }

    // ── reads ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the reader reads another business's tenant, merchants, shops, rules and reports")
    void reader_readsAnotherBusinessesData() throws Exception {
        String reader = reader();

        mockMvc.perform(as(get("/loyalty/reports/tenant"), reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchants").value(1));
        mockMvc.perform(as(get("/loyalty/reports/merchant/{id}", merchant), reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").value(merchant.toString()));
        mockMvc.perform(as(get("/loyalty/reports/merchants/full"), reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id", hasItem(merchant.toString())));
        mockMvc.perform(as(get("/loyalty/merchants"), reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id", hasItem(merchant.toString())));
        mockMvc.perform(as(get("/loyalty/shops"), reader)).andExpect(status().isOk());
        mockMvc.perform(as(get("/loyalty/rules"), reader)).andExpect(status().isOk());
        mockMvc.perform(as(get("/loyalty/invoices/merchant/{id}", merchant), reader)).andExpect(status().isOk());
        mockMvc.perform(as(get("/loyalty/tenants/{id}/members", tenant), reader)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("platform-wide reads need no tenant, and the picker lists every tenant")
    void reader_readsThePlatformAndPicksAnyTenant() throws Exception {
        String reader = reader();

        mockMvc.perform(get("/loyalty/reports/operator").header("Authorization", bearer(reader)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/loyalty/tenants/me").header("Authorization", bearer(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem(tenant.toString())));
    }

    @Test
    @DisplayName("the same token without the permission is refused every one of those reads")
    void withoutThePermission_theReadsAre403() throws Exception {
        String alone = productOfficerAlone();

        mockMvc.perform(as(get("/loyalty/reports/tenant"), alone)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/loyalty/reports/merchant/{id}", merchant), alone)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/loyalty/merchants"), alone)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/loyalty/vouchers").param("status", "ISSUED"), alone))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/loyalty/reports/operator").header("Authorization", bearer(alone)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("one customer's activity is support work: still 403 for the reader")
    void customerLevelReads_stayClosed() throws Exception {
        String reader = reader();

        mockMvc.perform(as(get("/loyalty/reports/user/phone/{phone}", "+263771234567"), reader))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/loyalty/reports/points/user/phone/{phone}", "+263771234567")
                        .param("from", "2026-01-01").param("to", "2026-12-31"), reader))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263771234567"), reader))
                .andExpect(status().isForbidden());
    }

    // ── no voucher code, anywhere ───────────────────────────────────────────

    @Test
    @DisplayName("the voucher list, report and detail carry no code for the reader")
    void reader_neverSeesAVoucherCode() throws Exception {
        String reader = reader();

        mockMvc.perform(as(get("/loyalty/vouchers").param("status", "ISSUED"), reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(voucher.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].code").value(nullValue()));
        mockMvc.perform(as(get("/loyalty/reports/vouchers/tenant"), reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vouchers.content[0].id").value(voucher.getId().toString()))
                .andExpect(jsonPath("$.data.vouchers.content[0].code").value(nullValue()));
        mockMvc.perform(as(get("/loyalty/reports/vouchers/detail/{id}", voucher.getId()), reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(nullValue()));

        String csv = mockMvc.perform(as(get("/loyalty/reports/vouchers/export"), reader))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains(voucher.getId().toString());
        assertThat(csv).doesNotContain(voucher.getCode()).doesNotContain(VoucherCodes.forExport(voucher.getCode()));
    }

    @Test
    @DisplayName("SUPER_ADMIN keeps the code, though its wildcard token carries the read permission too")
    void superAdmin_stillSeesTheCode() throws Exception {
        String withWildcard = TestJwtFactory.builder("super@test.local").role("SUPER_ADMIN")
                .permissions(List.of(ReportsPermissions.READ, "loyalty-support:read"))
                .sign(jwtSecret);

        for (String superAdmin : List.of(TestJwtFactory.superAdmin(jwtSecret), withWildcard)) {
            mockMvc.perform(as(get("/loyalty/vouchers").param("status", "ISSUED"), superAdmin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].code").value(voucher.getCode()));
            mockMvc.perform(as(get("/loyalty/reports/vouchers/detail/{id}", voucher.getId()), superAdmin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.code").value(voucher.getCode()));
        }
    }

    // ── writes ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every write is refused: merchant, rule, voucher issue, reversal, exchange rate")
    void reader_cannotWrite() throws Exception {
        String reader = reader();

        mockMvc.perform(as(post("/loyalty/merchants"), reader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Reader brand\",\"waiveFees\":true,\"waiveFeesReason\":\"t\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/loyalty/rules"), reader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"" + merchant + "\",\"transactionType\":\"PURCHASE\","
                                + "\"pointsPerUnit\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/loyalty/vouchers/issue"), reader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"" + merchant + "\",\"value\":5,"
                                + "\"assigneePhone\":\"+263771234567\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/loyalty/transactions/{id}/reverse", UUID.randomUUID()), reader))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/loyalty/exchange-rates").header("Authorization", bearer(reader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"ZWG\",\"ratePerUsd\":30}"))
                .andExpect(status().isForbidden());

        // Nothing was written behind the refusals.
        assertThat(merchantRepository.findByTenantId(tenant)).hasSize(1);
    }
}
