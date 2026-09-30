package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.service.VoucherPurchaseService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security surface for the pay-before-issue endpoints (V47). Purchase orders
 * gate real money collection, so the boundaries are asserted directly:
 * staff-only on the public surface, shared-secret on the S2S surface —
 * specific codes (401/403), never {@code is4xxClientError()}.
 */
class VoucherPurchaseControllerSecurityTest extends ControllerSecurityTestBase {

    @MockitoBean VoucherPurchaseService voucherPurchaseService;

    private static final String EMPTY_JSON = "{}";

    @Test
    void post_purchase_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/vouchers/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_purchase_as_customer_returns_403() throws Exception {
        String customerToken = TestJwtFactory.builder("customer@test.local")
                .role("CUSTOMER").phoneNumber("+263770000111").sign(jwtSecret);
        // The body must pass bean validation: argument binding (@Valid) runs
        // BEFORE method security, so an empty body would answer 400 and never
        // reach the @PreAuthorize this test exists to pin (the same ordering
        // that bit the V45 security tests via a missing required param).
        mockMvc.perform(post("/loyalty/vouchers/purchase")
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5.00}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void post_confirm_cash_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/vouchers/purchase/{ref}/confirm-cash", "VCH-4F9A1C22B7D3"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_confirm_card_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/vouchers/purchase/{ref}/confirm-card", "VCH-4F9A1C22B7D3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalCode\": \"A1B2C3\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_confirm_card_as_customer_returns_403() throws Exception {
        // A card-machine confirmation IS the payment proof, so it carries the
        // same staff gate as cash: no customer.
        String token = TestJwtFactory.builder("customer@test.local")
                .role("CUSTOMER").phoneNumber("+263770000111").sign(jwtSecret);
        mockMvc.perform(post("/loyalty/vouchers/purchase/{ref}/confirm-card", "VCH-4F9A1C22B7D3")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalCode\": \"A1B2C3\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashier_passes_the_role_gate_on_every_paid_sale_endpoint() throws Exception {
        // Cashiers sell vouchers at the till: the role gate admits SHOP_USER on
        // create, poll, confirm-cash and confirm-card. What a cashier may NOT
        // confirm (their own phone, a colleague, another shop) is decided in the
        // service, not here — so with the service mocked, each call is a 200.
        String token = TestJwtFactory.builder("cashier@test.local")
                .role("SHOP_USER").phoneNumber("+263770000111").sign(jwtSecret);
        String tenant = newTenant("till").toString();
        joinTenant(java.util.UUID.fromString(tenant), "cashier@test.local");
        mockMvc.perform(post("/loyalty/vouchers/purchase")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5.00}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/loyalty/vouchers/purchase/{ref}", "VCH-4F9A1C22B7D3")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk());
        mockMvc.perform(post("/loyalty/vouchers/purchase/{ref}/confirm-cash", "VCH-4F9A1C22B7D3")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk());
        mockMvc.perform(post("/loyalty/vouchers/purchase/{ref}/confirm-card", "VCH-4F9A1C22B7D3")
                        .header("Authorization", bearer(token))
                        .header("X-Tenant-Id", tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalCode\": \"A1B2C3\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void get_purchase_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/vouchers/purchase/{ref}", "VCH-4F9A1C22B7D3"))
                .andExpect(status().isUnauthorized());
    }

    // ----- internal S2S surface: shared secret, never the user JWT -----

    @Test
    void internal_get_without_internal_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/internal/voucher-orders/{ref}", "VCH-4F9A1C22B7D3"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internal_confirm_with_wrong_internal_token_returns_401() throws Exception {
        mockMvc.perform(patch("/loyalty/internal/voucher-orders/{ref}/confirm-payment", "VCH-4F9A1C22B7D3")
                        .header("X-Internal-Token", "definitely-not-the-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"TKZ-VCH-ABC123\",\"amountCents\":500}"))
                .andExpect(status().isUnauthorized());
    }
}
