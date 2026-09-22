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
    void by_admin_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/by-admin")
                        .param("email", "anyone@test.local"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void by_admin_with_wrong_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/by-admin")
                        .header("X-Internal-Token", "definitely-not-the-real-token")
                        .param("email", "anyone@test.local"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_email_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/{id}/admin-email", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_email_with_wrong_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/{id}/admin-email", UUID.randomUUID())
                        .header("X-Internal-Token", "not-the-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_email_with_correct_token_and_unknown_merchant_returns_404() throws Exception {
        // A merchantId naming nothing is a genuinely different fact from a
        // merchant with nobody on file, and the caller should see it in its logs.
        mockMvc.perform(get("/loyalty/internal/merchants/{id}/admin-email", UUID.randomUUID())
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void admin_email_returns_the_merchants_admin() throws Exception {
        UUID merchantId = seedMerchant("Chipo Electronics", "chipo@merchant.test");

        mockMvc.perform(get("/loyalty/internal/merchants/{id}/admin-email", merchantId)
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.adminEmail").value("chipo@merchant.test"));
    }

    @Test
    void admin_email_is_null_not_404_when_the_merchant_has_nobody_on_file() throws Exception {
        // The merchant exists; it just has no admin recorded. The consumer's
        // next step is the same either way (nobody to notify), so this is an
        // ordinary 200 — reserving the 404 for an id that names nothing.
        UUID merchantId = seedMerchant("Unclaimed Traders", null);

        mockMvc.perform(get("/loyalty/internal/merchants/{id}/admin-email", merchantId)
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.adminEmail").doesNotExist());
    }

    private UUID seedMerchant(String name, String adminEmail) {
        Tenant tenant = new Tenant();
        tenant.setName("Tenant " + UUID.randomUUID());
        tenant.setCode("T" + UUID.randomUUID().toString().substring(0, 8));
        tenant = tenantRepository.save(tenant);

        Merchant merchant = new Merchant();
        merchant.setTenantId(tenant.getId());
        merchant.setName(name);
        merchant.setAdminEmail(adminEmail);
        return merchantRepository.save(merchant).getId();
    }

    // ------------------------------------------------------------------
    // Batch merchant names — what marketplace-service renders "who is selling"
    // from. It holds ids and no names.
    // ------------------------------------------------------------------

    @Test
    void names_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/names")
                        .param("ids", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void names_with_wrong_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/names")
                        .header("X-Internal-Token", "not-the-real-token")
                        .param("ids", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void names_returns_a_row_per_known_id() throws Exception {
        UUID a = seedMerchant("Rudo Traders", "rudo@merchant.test");
        UUID b = seedMerchant("Chipo Electronics", "chipo2@merchant.test");

        mockMvc.perform(get("/loyalty/internal/merchants/names")
                        .header("X-Internal-Token", internalToken)
                        .param("ids", a + "," + b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchants.length()").value(2))
                .andExpect(jsonPath("$.merchants[?(@.merchantId=='" + a + "')].name")
                        .value("Rudo Traders"))
                .andExpect(jsonPath("$.merchants[?(@.merchantId=='" + b + "')].name")
                        .value("Chipo Electronics"));
    }

    @Test
    void names_omits_an_unknown_id_rather_than_failing_the_batch() throws Exception {
        // One stale id must not cost the others their names: the consumer
        // renders no name for a missing row, which is what it would do for a
        // 404 anyway — but a 404 would take the whole page down with it.
        UUID known = seedMerchant("Tendai Grocers", "tendai@merchant.test");

        mockMvc.perform(get("/loyalty/internal/merchants/names")
                        .header("X-Internal-Token", internalToken)
                        .param("ids", known + "," + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchants.length()").value(1))
                .andExpect(jsonPath("$.merchants[0].merchantId").value(known.toString()));
    }

    @Test
    void every_known_merchant_yields_a_name_because_the_column_is_not_null() throws Exception {
        // There is no "known but nameless" merchant to serve: merchants.name is
        // VARCHAR(200) NOT NULL (V1__init) and the entity marks it
        // nullable = false, so the only reason a row is missing from the
        // response is that the id names nothing. An earlier revision of this
        // test tried to seed a null name and was refused by the constraint --
        // worth keeping as a case, because the consumer's null-tolerant parsing
        // is defensive hardening, NOT a shape this endpoint can currently emit.
        UUID merchantId = seedMerchant("Tariro Hardware", "tariro@merchant.test");

        mockMvc.perform(get("/loyalty/internal/merchants/names")
                        .header("X-Internal-Token", internalToken)
                        .param("ids", merchantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchants.length()").value(1))
                .andExpect(jsonPath("$.merchants[0].merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.merchants[0].name").value("Tariro Hardware"));
    }

    @Test
    void names_with_no_ids_is_an_empty_list_not_a_400() throws Exception {
        // A page with nothing on it legitimately needs no names.
        mockMvc.perform(get("/loyalty/internal/merchants/names")
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchants.length()").value(0));
    }

    @Test
    void names_refuses_an_unbounded_batch() throws Exception {
        String tooMany = java.util.stream.Stream.generate(() -> UUID.randomUUID().toString())
                .limit(201)
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(get("/loyalty/internal/merchants/names")
                        .header("X-Internal-Token", internalToken)
                        .param("ids", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("too_many_ids"));
    }

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

    @Test
    void by_admin_with_correct_token_and_unknown_email_returns_404() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/by-admin")
                        .header("X-Internal-Token", internalToken)
                        .param("email", "nobody-here@test.local"))
                .andExpect(status().isNotFound());
    }

    @Test
    void by_admin_with_correct_token_and_blank_email_returns_400() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/by-admin")
                        .header("X-Internal-Token", internalToken)
                        .param("email", ""))
                .andExpect(status().isBadRequest());
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

    @Test
    void ids_by_admin_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/ids-by-admin")
                        .param("email", "anyone@test.local"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ids_by_admin_with_correct_token_and_blank_email_returns_400() throws Exception {
        mockMvc.perform(get("/loyalty/internal/merchants/ids-by-admin")
                        .header("X-Internal-Token", internalToken)
                        .param("email", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ids_by_admin_with_correct_token_and_unknown_email_returns_200_emptyList() throws Exception {
        // Unlike /by-admin (404 on miss), the list endpoint returns 200 with an
        // empty merchantIds array so the caller treats "owns nothing" uniformly.
        mockMvc.perform(get("/loyalty/internal/merchants/ids-by-admin")
                        .header("X-Internal-Token", internalToken)
                        .param("email", "nobody-here@test.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantIds").isArray())
                .andExpect(jsonPath("$.merchantIds").isEmpty());
    }
}
