package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lifting a fraud hold is the one operation that can make a suspended account
 * spendable again, so who may call it is the whole contract.
 *
 * <p>Every assertion names a specific status. {@code is4xxClientError()} would
 * pass on a Spring Security 401 raised before the controller ran, which is how a
 * missing route or a wrong {@code @PreAuthorize} hides.
 */
class LoyaltyUserAdminControllerSecurityTest extends ControllerSecurityTestBase {

    private static String path(UUID userId) {
        return "/loyalty/users/" + userId + "/unblock";
    }

    @Test
    @DisplayName("no token is 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(post(path(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a CUSTOMER cannot lift their own hold — that would undo the whole mechanism")
    void customer_isForbidden() throws Exception {
        UUID tenantId = newTenant("unblock-cust");
        joinTenant(tenantId, "customer@test.local");

        mockMvc.perform(post(path(UUID.randomUUID()))
                        .header("Authorization", bearer(jwt("customer@test.local", "CUSTOMER")))
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a SHOP_USER cannot lift a hold either")
    void shopUser_isForbidden() throws Exception {
        UUID tenantId = newTenant("unblock-shop");
        joinTenant(tenantId, "cashier@test.local");

        mockMvc.perform(post(path(UUID.randomUUID()))
                        .header("Authorization", bearer(jwt("cashier@test.local", "SHOP_USER")))
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin without the tenant header is 400, not a silent cross-tenant write")
    void admin_withoutTenantHeader_isBadRequest() throws Exception {
        mockMvc.perform(post(path(UUID.randomUUID()))
                        .header("Authorization", bearer(jwt("admin@test.local", "MERCHANT_ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an admin unblocking an unknown user is 404 — proof the role check passed")
    void admin_unknownUser_isNotFound() throws Exception {
        UUID tenantId = newTenant("unblock-admin");
        joinTenant(tenantId, "admin@test.local");

        mockMvc.perform(post(path(UUID.randomUUID()))
                        .header("Authorization", bearer(jwt("admin@test.local", "MERCHANT_ADMIN")))
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isNotFound());
    }
}
