package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security surface tests for MerchantController. Tenant scoping here matters
 * because cross-tenant merchant access leaks fee structures, billing cycles,
 * and the existence of competitor onboarding.
 */
class MerchantControllerSecurityTest extends ControllerSecurityTestBase {

    @MockitoBean MerchantService merchantService;

    // Minimal body passing MerchantRequest validation so @PreAuthorize is reached.
    private static final String VALID_MERCHANT_BODY = """
            {"name":"Test Merchant","billingCycle":"MONTHLY"}
            """;

    @Test
    void post_merchant_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MERCHANT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_merchants_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/merchants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_merchants_with_malformed_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/merchants")
                        .header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customer_cannot_create_merchant() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/merchants")
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MERCHANT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_activate_merchant() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/merchants/{id}/activate", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_deactivate_merchant() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/merchants/{id}/deactivate", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_list_merchants() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(get("/loyalty/merchants")
                        .header("Authorization", bearer(customerToken))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_without_tenant_header_returns_400() throws Exception {
        String admin = jwt("admin@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/merchants")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void admin_who_is_not_member_of_tenant_returns_403() throws Exception {
        UUID otherTenant = newTenant("merchant-cross");
        String stranger = jwt("stranger@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/merchants")
                        .header("Authorization", bearer(stranger))
                        .header("X-Tenant-Id", otherTenant.toString()))
                .andExpect(status().isForbidden());
    }
}
