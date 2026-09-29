package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.controller.VoucherController.PhoneVoucherView;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who sees voucher CODES on {@code GET /loyalty/vouchers/users/by-phone/{phone}/active}.
 * A code is a bearer credential that any till of the merchant will redeem, so
 * the till's own role (SHOP_USER) sees a customer's vouchers without them.
 * Pure JUnit — {@code VoucherControllerSecurityTest} covers the same rule
 * through the full filter chain, but needs Docker.
 */
class PhoneVoucherViewTest {

    private static final String CUSTOMER = "+263770000900";
    private static final String CASHIER = "+263770000555";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCashierLookingUpACustomer_getsTheListWithoutCodes() {
        as(CASHIER, "ROLE_SHOP_USER");
        assertThat(VoucherController.requireCallerMayListPhoneVouchers(CUSTOMER))
                .isEqualTo(PhoneVoucherView.WITHOUT_CODES);
    }

    @Test
    void aCashierWithNoPhoneClaim_stillGetsNoCodes() {
        as(null, "ROLE_SHOP_USER");
        assertThat(VoucherController.requireCallerMayListPhoneVouchers(CUSTOMER))
                .isEqualTo(PhoneVoucherView.WITHOUT_CODES);
    }

    @Test
    void aCashierLookingUpTheirOwnPhone_seesTheirOwnCodes() {
        as(CASHIER, "ROLE_SHOP_USER");
        assertThat(VoucherController.requireCallerMayListPhoneVouchers(CASHIER))
                .isEqualTo(PhoneVoucherView.WITH_CODES);
    }

    @Test
    void theCustomer_seesTheirOwnCodes() {
        as(CUSTOMER, "ROLE_CUSTOMER");
        assertThat(VoucherController.requireCallerMayListPhoneVouchers(CUSTOMER))
                .isEqualTo(PhoneVoucherView.WITH_CODES);
    }

    @Test
    void adminRoles_keepSeeingCodes() {
        for (String role : new String[] {"ROLE_SHOP_ADMIN", "ROLE_MERCHANT_ADMIN", "ROLE_SUPER_ADMIN"}) {
            as(CASHIER, role);
            assertThat(VoucherController.requireCallerMayListPhoneVouchers(CUSTOMER))
                    .as(role).isEqualTo(PhoneVoucherView.WITH_CODES);
        }
    }

    @Test
    void aCashierWhoIsAlsoAnAdmin_isAnAdmin() {
        as(CASHIER, "ROLE_SHOP_USER", "ROLE_SHOP_ADMIN");
        assertThat(VoucherController.requireCallerMayListPhoneVouchers(CUSTOMER))
                .isEqualTo(PhoneVoucherView.WITH_CODES);
    }

    @Test
    void anotherCustomer_isStillRefused() {
        as(CASHIER, "ROLE_CUSTOMER");
        assertThatThrownBy(() -> VoucherController.requireCallerMayListPhoneVouchers(CUSTOMER))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode())
                .isEqualTo("NOT_PHONE_OWNER");
    }

    private static void as(String phone, String... roles) {
        var auth = new UsernamePasswordAuthenticationToken("caller@test.local", null,
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(UUID.randomUUID(), UUID.randomUUID(), phone, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
