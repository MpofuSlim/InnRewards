package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.TenantMember;
import com.innbucks.loyaltyservice.service.ShopService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security + behaviour tests for {@link TenantController} after tenant "join"
 * was folded into registration and membership moved to be user-UUID based with
 * an email fallback.
 *
 * <p>{@code TenantService} is intentionally the real bean here (not mocked) so
 * {@code POST /loyalty/tenants} actually writes the {@code tenant_members} row,
 * and {@code TenantContext}'s dual-mode check runs against real rows. Only the
 * downstream {@link ShopService} is mocked — a tenant-scoped GET is the vehicle
 * for proving the membership check admits (or rejects) a caller.
 */
class TenantControllerSecurityTest extends ControllerSecurityTestBase {

    /** Mocked so the tenant-scoped GET used to probe the membership gate has a
     *  collaborator. When membership passes, the controller delegates here. */
    @MockitoBean ShopService shopService;

    // --- POST /loyalty/tenants — registration attaches the member by UUID ------

    @Test
    void create_with_id_attaches_that_user_as_a_member() throws Exception {
        UUID userId = UUID.randomUUID();
        String code = "acme-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"id":"%s","code":"%s","name":"Acme Coffee"}
                """.formatted(userId, code);

        String superAdmin = TestJwtFactory.superAdmin(jwtSecret);
        mockMvc.perform(post("/loyalty/tenants")
                        .header("Authorization", bearer(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value(code));

        // A member row keyed on the supplied userId now exists (email left null).
        UUID created = tenantRepository.findAllByCode(code).get(0).getId();
        assertThat(tenantMemberRepository.existsByTenantIdAndUserId(created, userId)).isTrue();
        List<TenantMember> rows = tenantMemberRepository.findByTenantId(created);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isEqualTo(userId);
        assertThat(rows.get(0).getEmail()).isNull();
    }

    @Test
    void create_without_id_is_rejected_400() throws Exception {
        // id is now @NotNull — the request shape changed.
        String body = """
                {"code":"missing-id","name":"No Id Co"}
                """;
        String superAdmin = TestJwtFactory.superAdmin(jwtSecret);
        mockMvc.perform(post("/loyalty/tenants")
                        .header("Authorization", bearer(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_requires_super_admin() throws Exception {
        String body = """
                {"id":"%s","code":"x","name":"X"}
                """.formatted(UUID.randomUUID());
        String merchantAdmin = jwt("merchant@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(post("/loyalty/tenants")
                        .header("Authorization", bearer(merchantAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_duplicate_name_returns_409() throws Exception {
        String superAdmin = TestJwtFactory.superAdmin(jwtSecret);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "Zuva Petroleum " + suffix;
        String first = """
                {"id":"%s","code":"z1-%s","name":"%s"}
                """.formatted(UUID.randomUUID(), suffix, name);
        mockMvc.perform(post("/loyalty/tenants").header("Authorization", bearer(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated());

        // Same name (case-insensitive), different id + code → rejected, not a 2nd tenant.
        String dup = """
                {"id":"%s","code":"z2-%s","name":"%s"}
                """.formatted(UUID.randomUUID(), suffix, name.toUpperCase());
        mockMvc.perform(post("/loyalty/tenants").header("Authorization", bearer(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON).content(dup))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_NAME_TAKEN"));
    }

    // --- Dual-mode membership check --------------------------------------------

    @Test
    void token_carrying_userId_passes_membership_check() throws Exception {
        // The whole point of the UUID path: a member added by userId is admitted
        // on a tenant-scoped call when the JWT carries that userId claim.
        UUID tenantId = newTenant("uuid-member");
        UUID userId = UUID.randomUUID();
        joinTenantByUserId(tenantId, userId);
        when(shopService.list(any(), any(), any())).thenReturn(Page.empty());

        String token = TestJwtFactory.builder("uuid-admin@test.local")
                .role("MERCHANT_ADMIN").userId(userId).sign(jwtSecret);
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void legacy_email_only_member_still_passes_via_fallback() throws Exception {
        // Backward-compat proof: a member row with only an email (no userId) and a
        // token with no userId claim must still be admitted by the email fallback.
        UUID tenantId = newTenant("email-member");
        joinTenant(tenantId, "legacy@test.local");
        when(shopService.list(any(), any(), any())).thenReturn(Page.empty());

        String token = jwt("legacy@test.local", "MERCHANT_ADMIN"); // no userId claim
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void non_member_userId_token_is_rejected_403() throws Exception {
        // A userId that matches no membership row (and an email that matches none
        // either) must be denied — neither arm of the dual check admits them.
        UUID tenantId = newTenant("no-member");
        String token = TestJwtFactory.builder("stranger@test.local")
                .role("MERCHANT_ADMIN").userId(UUID.randomUUID()).sign(jwtSecret);
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isForbidden());
    }

    // --- GET /loyalty/tenants/me — dual-mode (userId OR email) listing ----------

    @Test
    void tenants_me_lists_tenant_attached_by_user_uuid() throws Exception {
        // Regression: a user attached via the UUID flow (email column null) must
        // appear in /tenants/me. Before the dual-mode fix, findMine queried by
        // email only, so a UUID-attached member saw an empty list even though the
        // membership existed and worked via X-Tenant-Id.
        UUID tenantId = newTenant("me-uuid");
        UUID userId = UUID.randomUUID();
        joinTenantByUserId(tenantId, userId);

        String token = TestJwtFactory.builder("me-uuid@test.local")
                .role("MERCHANT_ADMIN").userId(userId).sign(jwtSecret);
        mockMvc.perform(get("/loyalty/tenants/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(tenantId.toString()));
    }

    @Test
    void tenants_me_lists_legacy_email_member_via_fallback() throws Exception {
        // The email arm still works for legacy rows / tokens without a userId.
        UUID tenantId = newTenant("me-email");
        joinTenant(tenantId, "me-legacy@test.local");

        String token = jwt("me-legacy@test.local", "MERCHANT_ADMIN"); // no userId claim
        mockMvc.perform(get("/loyalty/tenants/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(tenantId.toString()));
    }

    // --- The join endpoint is gone ---------------------------------------------
    // No HTTP-status assertion for the removed POST /loyalty/tenants/{id}/join:
    // this service's GlobalExceptionHandler has a catch-all @ExceptionHandler(
    // Exception.class) that maps an unmapped path's NoResourceFoundException to
    // 500 (not 404), so pinning a status here would assert that framework/handler
    // quirk rather than the feature. The removal is covered by the controller no
    // longer declaring the mapping and by the create-attaches-member tests, which
    // prove the member is attached at registration instead of via a join call.
}
