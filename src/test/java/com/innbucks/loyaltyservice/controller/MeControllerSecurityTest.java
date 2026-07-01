package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /loyalty/users/me/wallet is the customer-facing "what do I have" endpoint;
 * its security surface is "must be authenticated AND the JWT must carry a
 * phoneNumber claim". This test pins that contract.
 */
class MeControllerSecurityTest extends ControllerSecurityTestBase {

    @Test
    void wallet_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/users/me/wallet"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wallet_with_token_but_no_phone_claim_returns_400() throws Exception {
        // SUPER_ADMIN-style token: no phone claim attached.
        String token = TestJwtFactory.builder("admin@test.local")
                .role("SUPER_ADMIN").sign(jwtSecret);
        mockMvc.perform(get("/loyalty/users/me/wallet")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wallet_with_customer_and_phone_returns_200_with_phone_in_body() throws Exception {
        String token = TestJwtFactory.builder("customer@test.local")
                .role("CUSTOMER").tier(1).verified(true)
                .phoneNumber("+263770000099")
                .sign(jwtSecret);
        mockMvc.perform(get("/loyalty/users/me/wallet")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber").value("+263770000099"))
                .andExpect(jsonPath("$.data.totalPoints").exists())
                .andExpect(jsonPath("$.data.totalVouchers").exists());
    }
}
