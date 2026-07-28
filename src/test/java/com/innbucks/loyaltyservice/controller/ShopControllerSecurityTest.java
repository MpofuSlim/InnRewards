package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.service.ShopCheckoutService;
import com.innbucks.loyaltyservice.service.ShopService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security surface tests for ShopController. Shops are the per-outlet bucket
 * staff get scoped to via SHOP_ADMIN / SHOP_USER roles, so the role + tenant
 * boundaries here decide who can onboard which staff in user-service later.
 */
class ShopControllerSecurityTest extends ControllerSecurityTestBase {

    @MockitoBean ShopService shopService;
    @MockitoBean ShopCheckoutService shopCheckoutService;

    private static final String VALID_SHOP_BODY = """
            {"merchantId":"b4c0d2e3-2345-6789-abcd-ef0123456789","name":"Avondale"}
            """;

    // SHOP_ADMIN/SHOP_USER carry merchantId in the JWT, so the guest-checkout
    // body never needs one — just the walk-in customer's phone + the cash paid.
    private static final String VALID_GUEST_BODY = """
            {"phoneNumber":"+263771234567","cashAmount":10.00}
            """;

    @Test
    void post_shop_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHOP_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_shops_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/shops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_shop_by_id_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/shops/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_shops_with_malformed_token_returns_401() throws Exception {
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customer_cannot_create_shop() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/shops")
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHOP_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_update_shop() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(put("/loyalty/shops/{id}", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SHOP_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void customer_cannot_activate_shop() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/shops/{id}/activate", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_without_tenant_header_returns_400() throws Exception {
        String admin = jwt("admin@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void admin_who_is_not_member_of_tenant_returns_403() throws Exception {
        UUID otherTenant = newTenant("shop-cross");
        String stranger = jwt("stranger@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(get("/loyalty/shops")
                        .header("Authorization", bearer(stranger))
                        .header("X-Tenant-Id", otherTenant.toString()))
                .andExpect(status().isForbidden());
    }

    // --- Guest checkout (earn for an unregistered customer) ---------------------

    // A04/A01: guest-checkout is merchant-authenticated again. No bearer token -> 401.
    @Test
    void guest_checkout_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/loyalty/shops/{shopId}/guest-checkout", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_GUEST_BODY))
                .andExpect(status().isUnauthorized());
    }

    // A04/A01: a CUSTOMER is not a shop operator -> 403 (guest-checkout is limited
    // to SHOP_USER/SHOP_ADMIN/MERCHANT_ADMIN/SUPER_ADMIN).
    @Test
    void customer_cannot_guest_checkout_returns_403() throws Exception {
        String customerToken = jwt("customer@test.local", "CUSTOMER");
        mockMvc.perform(post("/loyalty/shops/{shopId}/guest-checkout", UUID.randomUUID())
                        .header("Authorization", bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_GUEST_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void guest_checkout_for_shop_under_other_merchant_returns_403() throws Exception {
        // The shop belongs to a DIFFERENT merchant than the caller's JWT scope, so
        // even a fully-authenticated shopkeeper can't earn through someone else's shop.
        // (Public for anonymous callers, but the ownership guard STILL fires when a
        // merchant JWT is presented.)
        UUID tenantId = newTenant("shop-guest-cross");
        joinTenant(tenantId, "cashier@test.local");
        UUID callerMerchant = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();

        when(shopService.get(any(), eq(shopId)))
                .thenReturn(new Dtos.ShopResponse(shopId, tenantId, otherMerchant,
                        "Someone Else's Shop", "addr", Shop.Status.ACTIVE, Instant.now()));

        String cashier = TestJwtFactory.shopAdmin("cashier@test.local", callerMerchant, shopId, jwtSecret);
        mockMvc.perform(post("/loyalty/shops/{shopId}/guest-checkout", shopId)
                        .header("Authorization", bearer(cashier))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_GUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_OWNED"));
    }

    @Test
    void shop_admin_guest_checkout_earns_points_returns_201() throws Exception {
        UUID tenantId = newTenant("shop-guest-ok");
        joinTenant(tenantId, "cashier@test.local");
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        UUID loyaltyUserId = UUID.randomUUID();
        UUID purchaseTxnId = UUID.randomUUID();

        when(shopService.get(any(), eq(shopId)))
                .thenReturn(new Dtos.ShopResponse(shopId, tenantId, merchantId,
                        "Pizza Inn Avondale", "addr", Shop.Status.ACTIVE, Instant.now()));
        // Cash-only: the controller MUST pass ZERO points so the guest earns but
        // never redeems — eq(BigDecimal.ZERO) pins that contract.
        when(shopCheckoutService.checkout(eq(shopId), eq("+263771234567"), any(), eq(BigDecimal.ZERO), any()))
                .thenReturn(new ShopCheckoutService.Result(shopId, merchantId, tenantId, loyaltyUserId,
                        new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("10.0000"),
                        new BigDecimal("10.0000"), purchaseTxnId, null));

        String cashier = TestJwtFactory.shopAdmin("cashier@test.local", merchantId, shopId, jwtSecret);
        mockMvc.perform(post("/loyalty/shops/{shopId}/guest-checkout", shopId)
                        .header("Authorization", bearer(cashier))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_GUEST_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.loyaltyUserId").value(loyaltyUserId.toString()))
                .andExpect(jsonPath("$.data.merchantId").value(merchantId.toString()))
                // pointsEarned is a BigDecimal -> JSON number; assert presence rather
                // than an exact numeric match to avoid Double/BigDecimal matcher flakiness.
                .andExpect(jsonPath("$.data.pointsEarned").exists());

        // The reference is generated by the backend ("SHOP-" + UUID), never taken
        // from the client — mirrors /payments/shop-checkout.
        verify(shopCheckoutService).checkout(
                eq(shopId), eq("+263771234567"), any(), eq(BigDecimal.ZERO), startsWith("SHOP-"));
    }

    @Test
    void merchant_admin_guest_checkout_needs_no_body_merchant_id() throws Exception {
        // MERCHANT_ADMIN carries no merchantId claim. With the field gone from the
        // request, the merchant is taken from the shop and the caller is scoped purely
        // by tenant membership — so a tenant member checks out without a 403.
        UUID tenantId = newTenant("shop-guest-ma");
        joinTenant(tenantId, "admin@test.local");
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        UUID loyaltyUserId = UUID.randomUUID();

        when(shopService.get(any(), eq(shopId)))
                .thenReturn(new Dtos.ShopResponse(shopId, tenantId, merchantId,
                        "Pizza Inn Westgate", "addr", Shop.Status.ACTIVE, Instant.now()));
        when(shopCheckoutService.checkout(eq(shopId), eq("+263771234567"), any(), eq(BigDecimal.ZERO), any()))
                .thenReturn(new ShopCheckoutService.Result(shopId, merchantId, tenantId, loyaltyUserId,
                        new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("10.0000"),
                        new BigDecimal("10.0000"), UUID.randomUUID(), null));

        String admin = jwt("admin@test.local", "MERCHANT_ADMIN");
        mockMvc.perform(post("/loyalty/shops/{shopId}/guest-checkout", shopId)
                        .header("Authorization", bearer(admin))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_GUEST_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.merchantId").value(merchantId.toString()));
    }
}
