package com.innbucks.loyaltyservice.controller;

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
