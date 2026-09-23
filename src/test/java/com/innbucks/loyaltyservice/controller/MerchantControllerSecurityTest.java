package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    // ---- the admin binding: SUPER_ADMIN only --------------------------------
    //
    // Rebinding a merchant moves it from one person to another - their sign-in
    // scope, their management rights here, their invoices. Each refusal below
    // uses a caller who IS a member of the tenant, so the only gate that can
    // refuse them is the role check, and asserts the service never ran.

    private static final String REBIND_BODY = """
            {"adminEmail":"rudo@chikwanha-traders.co.zw"}
            """;

    @Test
    void merchant_admin_cannot_rebind_a_merchant() throws Exception {
        UUID tenant = newTenant("rebind-ma");
        joinTenant(tenant, "tendai@test.local");
        mockMvc.perform(put("/loyalty/merchants/{id}/admin-email", UUID.randomUUID())
                        .header("Authorization", bearer(jwt("tendai@test.local", "MERCHANT_ADMIN")))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REBIND_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403 FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("You don't have permission to do that."));
        verifyNoInteractions(merchantService);
    }

    @Test
    void shop_admin_cannot_rebind_a_merchant() throws Exception {
        UUID tenant = newTenant("rebind-sa");
        joinTenant(tenant, "cashier@test.local");
        mockMvc.perform(put("/loyalty/merchants/{id}/admin-email", UUID.randomUUID())
                        .header("Authorization", bearer(jwt("cashier@test.local", "SHOP_ADMIN")))
                        .header("X-Tenant-Id", tenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REBIND_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You don't have permission to do that."));
        verifyNoInteractions(merchantService);
    }

    @Test
    void merchant_admin_cannot_unbind_a_merchant() throws Exception {
        UUID tenant = newTenant("unbind-ma");
        joinTenant(tenant, "tendai@test.local");
        mockMvc.perform(delete("/loyalty/merchants/{id}/admin-email", UUID.randomUUID())
                        .header("Authorization", bearer(jwt("tendai@test.local", "MERCHANT_ADMIN")))
                        .header("X-Tenant-Id", tenant.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You don't have permission to do that."));
        verifyNoInteractions(merchantService);
    }

    @Test
    void rebind_without_token_returns_401() throws Exception {
        mockMvc.perform(put("/loyalty/merchants/{id}/admin-email", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REBIND_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(merchantService);
    }

    @Test
    void rebind_with_a_malformed_email_is_400_with_the_field_named() throws Exception {
        mockMvc.perform(put("/loyalty/merchants/{id}/admin-email", UUID.randomUUID())
                        .header("Authorization", bearer(jwt("ops@test.local", "SUPER_ADMIN")))
                        .header("X-Tenant-Id", newTenant("rebind-bad").toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adminEmail":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400 BAD_REQUEST"))
                .andExpect(jsonPath("$.data.adminEmail").value("must be a well-formed email address"));
        verifyNoInteractions(merchantService);
    }

    @Test
    void rebind_with_an_empty_body_is_400_never_read_as_clear() throws Exception {
        // Clearing is its own verb (DELETE). An empty PUT must not unbind a merchant.
        mockMvc.perform(put("/loyalty/merchants/{id}/admin-email", UUID.randomUUID())
                        .header("Authorization", bearer(jwt("ops@test.local", "SUPER_ADMIN")))
                        .header("X-Tenant-Id", newTenant("rebind-empty").toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.adminEmail").value("must not be blank"));
        verifyNoInteractions(merchantService);
    }
}
