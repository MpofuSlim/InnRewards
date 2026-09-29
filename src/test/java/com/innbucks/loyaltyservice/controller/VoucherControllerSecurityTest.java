package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security surface tests for VoucherController. Money handling endpoints — the
 * role boundaries here are non-negotiable, so we assert them directly rather
 * than trusting the @PreAuthorize annotations to be right.
 */
class VoucherControllerSecurityTest extends ControllerSecurityTestBase {

    // Mock the services so we never exercise their logic — these tests are
    // about the security filter chain + @PreAuthorize, not about voucher math.
    @MockitoBean VoucherService voucherService;

    private static final String EMPTY_JSON = "{}";

    @Test
    void post_issue_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/vouchers/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_redeem_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/vouchers/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_active_for_phone_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263770000001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customer_cannot_view_someone_elses_phone_vouchers() throws Exception {
        // CUSTOMER token bound to one phone trying to read another phone's
        // vouchers must 403 NOT_PHONE_OWNER (the IDOR gate). Alice is a member
        // of the tenant (so she clears the new tenant-scope gate) but the phone
        // in the path isn't hers, so the owner check still rejects.
        UUID tenant = newTenant("vch-phone-idor");
        joinTenant(tenant, "alice@test.local");
        String aliceToken = com.innbucks.loyaltyservice.testsupport.TestJwtFactory
                .builder("alice@test.local").role("CUSTOMER")
                .phoneNumber("+263770000111").sign(jwtSecret);
        mockMvc.perform(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263770000222")
                        .header("Authorization", bearer(aliceToken))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void active_for_phone_without_tenant_header_returns_400() throws Exception {
        // Stricter authz: the by-phone wallet lookup is now tenant-scoped, so a
        // caller with no X-Tenant-Id header is rejected before any data is read
        // — this is what closes the cross-tenant voucher-enumeration hole.
        String aliceToken = com.innbucks.loyaltyservice.testsupport.TestJwtFactory
                .builder("alice@test.local").role("CUSTOMER")
                .phoneNumber("+263770000111").sign(jwtSecret);
        mockMvc.perform(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263770000111")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void admin_stranger_to_tenant_cannot_enumerate_phone_vouchers_returns_403() throws Exception {
        // The core A01 fix: an admin from a DIFFERENT tenant (not a member of
        // the path tenant) can no longer read a customer's vouchers by phone.
        // TenantContext.requireTenant() rejects the non-member with 403 before
        // the (tenant-scoped) query runs.
        UUID otherTenant = newTenant("vch-phone-cross");
        String stranger = jwt("stranger@test.local", "MERCHANT_ADMIN"); // not a member
        mockMvc.perform(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263770000111")
                        .header("Authorization", bearer(stranger))
                        .header("X-Tenant-Id", otherTenant.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_can_view_their_own_phone_vouchers() throws Exception {
        // The mocked VoucherService needs an empty Page or PageResponse.from
        // NPEs trying to map a null result.
        org.mockito.Mockito.when(voucherService.activeForPhone(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(), org.springframework.data.domain.Pageable.unpaged(), 0));

        // Alice must be a member of the tenant she scopes the request to, then
        // the owner check admits her for her own phone.
        UUID tenant = newTenant("vch-phone-self");
        joinTenant(tenant, "alice@test.local");
        String aliceToken = com.innbucks.loyaltyservice.testsupport.TestJwtFactory
                .builder("alice@test.local").role("CUSTOMER")
                .phoneNumber("+263770000111").sign(jwtSecret);
        mockMvc.perform(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263770000111")
                        .header("Authorization", bearer(aliceToken))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void post_issue_with_malformed_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/vouchers/issue")
                        .header("Authorization", "Bearer not-a-real-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_issue_with_expired_token_returns_401() throws Exception {
        String expired = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.builder("admin@test.local")
                .role("MERCHANT_ADMIN").expired().sign(jwtSecret);
        mockMvc.perform(post("/loyalty/vouchers/issue")
                        .header("Authorization", bearer(expired))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_JSON))
                .andExpect(status().isUnauthorized());
    }

    // Bodies below pass @Valid so the request reaches the @PreAuthorize gate
    // (which is what we're actually asserting). The mocked services would
    // never be invoked because the role check rejects first.
    private static final String VALID_ISSUE_BODY = """
            {"value":5.00}
            """;
    private static final String VALID_BULK_BODY = """
            {"value":5.00,"quantity":1}
            """;

    @Test
    void customer_cannot_issue_voucher() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/vouchers/issue")
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ISSUE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_revoke_voucher() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/vouchers/{id}/revoke", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_bulk_issue() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/vouchers/issue-bulk")
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BULK_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_list_vouchers() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        // status is a REQUIRED request param on the list endpoint — omit it and
        // argument binding fails before any of the refusals under test run.
        mockMvc.perform(get("/loyalty/vouchers")
                        .param("status", "ISSUED")
                        .header("Authorization", bearer(customerToken))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_without_tenant_header_returns_400() throws Exception {
        String admin = jwt("admin@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/vouchers")
                        .param("status", "ISSUED")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    // --- SHOP_USER — till-operations role ---
    // SHOP_USER does the daily voucher ops at the till (list / redeem / mark-viewed)
    // but cannot issue, revoke or transfer vouchers, and its list never carries
    // a customer's codes. These pin both sides.

    private static final String VALID_REDEEM_BODY = """
            {"code":"VCH-AB12-CD34-EF56"}
            """;

    @Test
    void shop_user_can_redeem_voucher() throws Exception {
        org.mockito.Mockito.when(voucherService.redeem(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(com.innbucks.loyaltyservice.dto.Dtos.RedeemVoucherRequest.class)))
                .thenReturn(new com.innbucks.loyaltyservice.dto.Dtos.RedemptionResponse(
                        UUID.randomUUID(), UUID.randomUUID(), "REDEEMED",
                        0, new java.math.BigDecimal("5.00"),
                        java.time.Instant.now()));

        UUID tenant = newTenant("vch-shopuser-redeem");
        joinTenant(tenant, "till-user@test.local");
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.shopUser(
                "till-user@test.local", UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/loyalty/vouchers/redeem")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REDEEM_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void shop_user_lists_a_customers_active_vouchers_WITHOUT_their_codes() throws Exception {
        // A cashier sees that the customer holds a voucher and what it is worth,
        // never the code that spends it: redeem accepts a code from any till of
        // the merchant, so a code the cashier can read is a voucher the cashier
        // can spend with the customer nowhere near the shop.
        stubActiveVouchers("4829137605128368");

        UUID tenant = newTenant("vch-shopuser-byphone");
        joinTenant(tenant, "till-user@test.local");
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory
                .builder("till-user@test.local").role("SHOP_USER")
                .merchantId(UUID.randomUUID()).shopId(UUID.randomUUID())
                .phoneNumber("+263770000555").sign(jwtSecret);
        mockMvc.perform(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263770000900")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].value").exists())
                .andExpect(jsonPath("$.data.content[0].code").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void shop_user_looking_up_their_OWN_phone_sees_their_own_codes() throws Exception {
        stubActiveVouchers("4829137605128368");

        UUID tenant = newTenant("vch-shopuser-own");
        joinTenant(tenant, "till-user@test.local");
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory
                .builder("till-user@test.local").role("SHOP_USER")
                .merchantId(UUID.randomUUID()).shopId(UUID.randomUUID())
                .phoneNumber("+263770000555").sign(jwtSecret);
        mockMvc.perform(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263770000555")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].code").value("4829137605128368"));
    }

    @Test
    void shop_admin_still_sees_codes_on_the_by_phone_list() throws Exception {
        stubActiveVouchers("4829137605128368");

        UUID tenant = newTenant("vch-shopadmin-byphone");
        joinTenant(tenant, "shop-admin@test.local");
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.shopAdmin(
                "shop-admin@test.local", UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263770000900")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].code").value("4829137605128368"));
    }

    @Test
    void shop_user_cannot_transfer_a_voucher() throws Exception {
        // Transfer sends a fresh code to whatever phone the caller names. Refused
        // by @PreAuthorize, before the service is ever asked.
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.shopUser(
                "till-user@test.local", UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/loyalty/vouchers/{id}/transfer", UUID.randomUUID())
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toPhone\":\"+263770000777\"}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(voucherService);
    }

    @Test
    void shop_user_cannot_consume_a_qr() throws Exception {
        // Consume credits the CALLER; a till token has no reason to credit itself.
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.shopUser(
                "till-user@test.local", UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/loyalty/qr/consume")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"signature\":\"s\",\"userId\":\""
                                + UUID.randomUUID() + "\",\"reference\":\"r\"}"))
                .andExpect(status().isForbidden());
    }

    private void stubActiveVouchers(String code) {
        var voucher = new com.innbucks.loyaltyservice.dto.Dtos.VoucherResponse(
                UUID.randomUUID(), code, "ISSUED", "SINGLE_USE",
                UUID.randomUUID(), null, null, null,
                UUID.randomUUID(), "+263770000900", "Sedrick Nyanyiwa",
                null, null,
                null, null, null,
                1, new java.math.BigDecimal("5.00"), "USD", new java.math.BigDecimal("5.00"),
                java.time.Instant.now(), null, null, null, null, null,
                null, null);
        org.mockito.Mockito.when(voucherService.activeForPhone(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(voucher), org.springframework.data.domain.Pageable.unpaged(), 1));
    }

    @Test
    void shop_user_can_mark_voucher_viewed() throws Exception {
        UUID tenant = newTenant("vch-shopuser-viewed");
        joinTenant(tenant, "till-user@test.local");
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.shopUser(
                "till-user@test.local", UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/loyalty/vouchers/codes/{code}/viewed", "VCH-AB12-CD34-EF56")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void shop_user_cannot_issue_voucher() throws Exception {
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.shopUser(
                "till-user@test.local", UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/loyalty/vouchers/issue")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ISSUE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void shop_user_cannot_revoke_voucher() throws Exception {
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.shopUser(
                "till-user@test.local", UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/loyalty/vouchers/{id}/revoke", UUID.randomUUID())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    // 403: cross-tenant — admin valid + tenant exists, but they're not a member
    @Test
    void admin_who_is_not_a_member_of_tenant_returns_403() throws Exception {
        UUID otherTenant = newTenant("voucher-cross");
        // Caller email NOT added to tenant_members.
        String stranger = jwt("stranger@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/vouchers")
                        .param("status", "ISSUED")
                        .header("Authorization", bearer(stranger))
                        .header("X-Tenant-Id", otherTenant.toString()))
                .andExpect(status().isForbidden());
    }
}
