package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for the service-to-service lookup endpoints. These don't use
 * JWT — they're gated by the X-Internal-Token shared secret. The JwtFilter
 * is configured to skip /loyalty/internal/** entirely, so the only gate is
 * the controller's own header check.
 */
class InternalMerchantLookupControllerSecurityTest extends ControllerSecurityTestBase {

    @Value("${innbucks.internal-api-token}") String internalToken;

    @org.springframework.beans.factory.annotation.Autowired
    MerchantRepository merchantRepository;

















    @Test
    void get_shop_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/shops/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_shop_with_wrong_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/shops/{id}", UUID.randomUUID())
                        .header("X-Internal-Token", "still-not-the-real-token"))
                .andExpect(status().isUnauthorized());
    }

    // 404: correct token but the resource doesn't exist — proves the
    // header gate accepted the right token and we got through to lookup.
    @Test
    void get_shop_with_correct_token_and_unknown_id_returns_404() throws Exception {
        mockMvc.perform(get("/loyalty/internal/shops/{id}", UUID.randomUUID())
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isNotFound());
    }



    // A JWT is irrelevant here — internal endpoints ignore Authorization
    // entirely. This test guards against accidentally adding @PreAuthorize.
    @Test
    void jwt_is_ignored_on_internal_endpoints() throws Exception {
        String adminJwt = jwt("admin@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/internal/shops/{id}", UUID.randomUUID())
                        .header("Authorization", bearer(adminJwt)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void promote_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/loyalty/internal/users/promote")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+263770000001\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void promote_with_correct_token_and_blank_phone_returns_400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/loyalty/internal/users/promote")
                        .header("X-Internal-Token", internalToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void promote_with_correct_token_and_unknown_phone_returns_200_with_zero_count() throws Exception {
        // Replays / unknown phones are idempotent no-ops, not errors.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/loyalty/internal/users/promote")
                        .header("X-Internal-Token", internalToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+263777777777\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void shop_checkout_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/loyalty/internal/shop-checkout")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":\"" + UUID.randomUUID() + "\",\"phoneNumber\":\"0712345678\",\"cashAmount\":10.00}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shop_checkout_with_correct_token_and_unknown_shop_returns_404() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/loyalty/internal/shop-checkout")
                        .header("X-Internal-Token", internalToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":\"" + UUID.randomUUID() + "\",\"phoneNumber\":\"0712345678\",\"cashAmount\":10.00}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shop_checkout_with_no_amounts_returns_400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/loyalty/internal/shop-checkout")
                        .header("X-Internal-Token", internalToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":\"" + UUID.randomUUID() + "\",\"phoneNumber\":\"0712345678\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Merchants by ORGANIZATION — user-service's shop-staff scope. It
    // replaced ids-by-admin, which answered the same question by email.
    // ------------------------------------------------------------------

    @Test
    void ids_by_organization_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/ids-by-organization")
                        .param("organizationId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ids_by_organization_with_wrong_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/ids-by-organization")
                        .header("X-Internal-Token", "wrong-token")
                        .param("organizationId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ids_by_organization_without_an_organization_returns_400() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/ids-by-organization")
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ids_by_organization_for_an_organization_owning_nothing_is_an_empty_list_not_a_404() throws Exception {
        UUID org = UUID.randomUUID();
        mockMvc.perform(get("/loyalty/internal/merchants/ids-by-organization")
                        .header("X-Internal-Token", internalToken)
                        .param("organizationId", org.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(org.toString()))
                .andExpect(jsonPath("$.merchantIds").isArray())
                .andExpect(jsonPath("$.merchantIds").isEmpty());
    }

    @Test
    void ids_by_organization_returns_every_merchant_it_owns_and_nobody_elses() throws Exception {
        UUID org = UUID.randomUUID();
        UUID first = seedMerchant("Chicken Inn " + UUID.randomUUID(), org);
        UUID second = seedMerchant("Pizza Inn " + UUID.randomUUID(), org);
        seedMerchant("Someone Else " + UUID.randomUUID(), UUID.randomUUID());
        seedMerchant("Unowned " + UUID.randomUUID(), null);

        mockMvc.perform(get("/loyalty/internal/merchants/ids-by-organization")
                        .header("X-Internal-Token", internalToken)
                        .param("organizationId", org.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantIds.length()").value(2))
                .andExpect(jsonPath("$.merchantIds[0]").value(first.toString()))
                .andExpect(jsonPath("$.merchantIds[1]").value(second.toString()));
    }

    private UUID seedMerchant(String name, UUID organizationId) {
        Tenant tenant = new Tenant();
        tenant.setName("Tenant " + UUID.randomUUID());
        tenant.setCode("T" + UUID.randomUUID().toString().substring(0, 8));
        tenant = tenantRepository.save(tenant);

        Merchant merchant = new Merchant();
        merchant.setTenantId(tenant.getId());
        merchant.setName(name);
        merchant.setOrganizationId(organizationId);
        return merchantRepository.save(merchant).getId();
    }
}
