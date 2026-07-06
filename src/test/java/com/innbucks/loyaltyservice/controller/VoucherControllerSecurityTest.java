package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @MockitoBean VoucherTemplateService voucherTemplateService;

    private static final String EMPTY_JSON = "{}";

    @Test
    void post_template_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/vouchers/templates")
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
    void post_template_with_malformed_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/vouchers/templates")
                        .header("Authorization", "Bearer not-a-real-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_template_with_expired_token_returns_401() throws Exception {
        String expired = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.builder("admin@test.local")
                .role("MERCHANT_ADMIN").expired().sign(jwtSecret);
        mockMvc.perform(post("/loyalty/vouchers/templates")
                        .header("Authorization", bearer(expired))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_JSON))
                .andExpect(status().isUnauthorized());
    }

    // Bodies below pass @Valid so the request reaches the @PreAuthorize gate
    // (which is what we're actually asserting). The mocked services would
    // never be invoked because the role check rejects first.
    private static final String VALID_TEMPLATE_BODY = """
            {"name":"x","type":"SINGLE_USE","valueType":"AMOUNT","usageLimit":1}
            """;
    private static final String VALID_ISSUE_BODY = """
            {"templateId":"d6e2f4a5-4567-8901-bcde-f01234567890"}
            """;
    private static final String VALID_BULK_BODY = """
            {"templateId":"d6e2f4a5-4567-8901-bcde-f01234567890","quantity":1}
            """;

    @Test
    void customer_cannot_create_template() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/vouchers/templates")
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isForbidden());
    }

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
    void customer_cannot_list_templates() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(get("/loyalty/vouchers/templates")
                        .header("Authorization", bearer(customerToken))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_without_tenant_header_returns_400() throws Exception {
        String admin = jwt("admin@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/vouchers/templates")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    // --- SHOP_USER — till-operations role ---
    // SHOP_USER does the daily voucher ops at the till (list / redeem / mark-viewed)
    // but cannot create templates, issue vouchers, or revoke. These pin both sides.

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
                        0, new java.math.BigDecimal("5.00"), "AMOUNT",
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
    void shop_user_can_list_active_vouchers_by_phone() throws Exception {
        org.mockito.Mockito.when(voucherService.activeForPhone(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(), org.springframework.data.domain.Pageable.unpaged(), 0));

        // The by-phone endpoint is now tenant-scoped: the caller must send a
        // valid X-Tenant-Id and be a member of it. The admin-role owner-check
        // bypass still lets a shop user look up any phone WITHIN their tenant.
        UUID tenant = newTenant("vch-shopuser-byphone");
        joinTenant(tenant, "till-user@test.local");
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.shopUser(
                "till-user@test.local", UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(get("/loyalty/vouchers/users/by-phone/{phone}/active", "+263770000900")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk());
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
    void shop_user_cannot_create_voucher_template() throws Exception {
        String token = com.innbucks.loyaltyservice.testsupport.TestJwtFactory.shopUser(
                "till-user@test.local", UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/loyalty/vouchers/templates")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isForbidden());
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
        mockMvc.perform(get("/loyalty/vouchers/templates")
                        .header("Authorization", bearer(stranger))
                        .header("X-Tenant-Id", otherTenant.toString()))
                .andExpect(status().isForbidden());
    }
}
