package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end ownership of loyalty merchants by ORGANIZATION (V51), through the
 * real filter, tenant check, controller and service — nothing mocked but
 * user-service.
 *
 * <p>A business used to own its merchants through {@code merchants.admin_email}
 * matching the caller's login. Now the session's organization owns them: its
 * OWNERs and ADMINs are merchant admins here (the bare role in the token grants
 * nothing), and they are members of the programs their business created or
 * holds a merchant in. The cases below are the shapes that must be refused as
 * much as the ones that must work.
 */
class MerchantOrganizationOwnershipSecurityTest extends ControllerSecurityTestBase {

    private static final UUID ACME = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");
    private static final UUID RIVAL = UUID.fromString("9c2d4e6f-1a3b-4c5d-8e7f-0a1b2c3d4e60");

    @Autowired MerchantRepository merchantRepository;

    /** Waived so the zero-issue-fee guard is not what the test exercises. */
    private static String merchantBody(String name) {
        return """
                {"name":"%s","waiveFees":true,"waiveFeesReason":"ownership test"}
                """.formatted(name);
    }

    private UUID tenantOwnedBy(UUID organizationId) {
        Tenant t = new Tenant();
        t.setCode("ORG-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Program " + UUID.randomUUID());
        t.setOrganizationId(organizationId);
        return tenantRepository.save(t).getId();
    }

    private UUID merchantIn(UUID tenantId, UUID organizationId) {
        Merchant m = new Merchant();
        m.setTenantId(tenantId);
        m.setName("Merchant " + UUID.randomUUID());
        m.setOrganizationId(organizationId);
        return merchantRepository.save(m).getId();
    }

    /** A colleague added through /organizations: no staff role at all, just the organization. */
    private String colleague(UUID organizationId, String orgRole, List<String> products) {
        return TestJwtFactory.builder("colleague@acme.test")
                .role("CUSTOMER")
                .userId(UUID.randomUUID())
                .organization(organizationId, orgRole, products)
                .sign(jwtSecret);
    }

    @Test
    void anAdminColleague_withNoStaffRole_onboardsAMerchantForTheirOrganization() throws Exception {
        UUID tenant = tenantOwnedBy(ACME);

        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(colleague(ACME, "ADMIN", List.of("loyalty"))))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody("Chicken Inn " + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.organizationId").value(ACME.toString()));
    }

    @Test
    void aBareMerchantAdminRole_withNoOrganization_isRefused() throws Exception {
        // The pre-organization token shape: the role alone must not make
        // anyone a merchant admin, or a marketplace-only business could
        // administer loyalty.
        UUID tenant = tenantOwnedBy(ACME);
        joinTenant(tenant, "legacy@acme.test");
        String legacy = TestJwtFactory.builder("legacy@acme.test")
                .role("MERCHANT_ADMIN").withoutOrganization().sign(jwtSecret);

        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(legacy))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody("Legacy " + UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffOfTheOrganization_isRefused() throws Exception {
        UUID tenant = tenantOwnedBy(ACME);

        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(colleague(ACME, "STAFF", List.of("loyalty"))))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody("Staff " + UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBusinessWithoutTheLoyaltyProduct_isRefused_evenItsOwner() throws Exception {
        UUID tenant = tenantOwnedBy(ACME);
        joinTenant(tenant, "owner@acme.test");
        String marketplaceOnly = TestJwtFactory.builder("owner@acme.test")
                .role("MERCHANT_ADMIN")
                .organization(ACME, "OWNER", List.of("marketplace"))
                .sign(jwtSecret);

        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(marketplaceOnly))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody("Seller " + UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void namingAnotherBusiness_isRefused_ratherThanSilentlyIgnored() throws Exception {
        UUID tenant = tenantOwnedBy(ACME);
        String body = """
                {"name":"Rival Brand %s","waiveFees":true,"waiveFeesReason":"t","organizationId":"%s"}
                """.formatted(UUID.randomUUID(), RIVAL);

        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(colleague(ACME, "OWNER", List.of("loyalty"))))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_PERMITTED"));
    }

    @Test
    void superAdmin_onboardsAMerchantForANamedBusiness() throws Exception {
        UUID tenant = tenantOwnedBy(null);
        String body = """
                {"name":"On Behalf %s","waiveFees":true,"waiveFeesReason":"t","organizationId":"%s"}
                """.formatted(UUID.randomUUID(), RIVAL);

        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(TestJwtFactory.superAdmin(jwtSecret)))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.organizationId").value(RIVAL.toString()));
    }

    @Test
    void aColleague_worksInAProgramAnotherBusinessCreated_onlyOnTheirOwnMerchant() throws Exception {
        // RIVAL created the program; ACME holds a merchant in it. ACME's admin
        // is a member through that merchant — and may act on it, never on
        // RIVAL's merchant beside it.
        UUID tenant = tenantOwnedBy(RIVAL);
        UUID ours = merchantIn(tenant, ACME);
        UUID theirs = merchantIn(tenant, RIVAL);
        String acmeAdmin = colleague(ACME, "ADMIN", List.of("loyalty"));

        mockMvc.perform(post("/loyalty/merchants/{id}/deactivate", ours)
                        .header("Authorization", bearer(acmeAdmin))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/loyalty/merchants/{id}/deactivate", theirs)
                        .header("Authorization", bearer(acmeAdmin))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MERCHANT_OWNER"));
    }

    @Test
    void aBusinessWithNothingInAProgram_isNotAMemberOfIt() throws Exception {
        UUID tenant = tenantOwnedBy(RIVAL);
        merchantIn(tenant, RIVAL);

        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(colleague(ACME, "OWNER", List.of("loyalty"))))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody("Intruder " + UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }
}
