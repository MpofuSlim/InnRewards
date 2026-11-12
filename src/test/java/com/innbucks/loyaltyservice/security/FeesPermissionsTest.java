package com.innbucks.loyaltyservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may set what merchants pay InnBucks (V67): SUPER_ADMIN and holders of
 * {@code loyalty-fees:manage}, never a merchant. And who may see which staff
 * member set a fee: those two plus read-only platform access.
 */
class FeesPermissionsTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone@example.com", null, Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    void theCodeIsCharacterIdenticalToUserServicesCatalog() {
        assertThat(FeesPermissions.MANAGE).isEqualTo("loyalty-fees:manage");
        assertThat(FeesPermissions.HAS_MANAGE).contains("hasAuthority('loyalty-fees:manage')");
        // The code is the shape JwtFilter turns into a bare authority.
        assertThat(FeesPermissions.MANAGE).matches("^[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*)+$");
    }

    @Test
    void superAdmin_maySetFees_andSeesWhoSetThem() {
        authenticate("ROLE_SUPER_ADMIN");

        assertThat(FeesPermissions.callerMaySetFees()).isTrue();
        assertThat(FeesPermissions.callerSeesFeeAuthors()).isTrue();
    }

    @Test
    void aFinanceRoleHoldingTheCode_maySetFees() {
        authenticate("ROLE_PRODUCT_OFFICER", FeesPermissions.MANAGE);

        assertThat(FeesPermissions.callerMaySetFees()).isTrue();
    }

    @Test
    void aMerchantAdmin_mayNotSetFees_norSeeWhoSetThem() {
        authenticate("ROLE_MERCHANT_ADMIN");

        assertThat(FeesPermissions.callerMaySetFees()).isFalse();
        assertThat(FeesPermissions.callerSeesFeeAuthors()).isFalse();
    }

    @Test
    void aRoleNamedLikeTheCode_isNotTheCode() {
        // Only the bare authority JwtFilter grants for a perms entry counts.
        authenticate("ROLE_LOYALTY-FEES:MANAGE", "ROLE_loyalty-fees:manage");

        assertThat(FeesPermissions.callerMaySetFees()).isFalse();
    }

    @Test
    void readOnlyPlatformAccess_seesWhoSetAFee_butMayNotSetOne() {
        authenticate(ReportsPermissions.READ);

        assertThat(FeesPermissions.callerMaySetFees()).isFalse();
        assertThat(FeesPermissions.callerSeesFeeAuthors()).isTrue();
    }

    @Test
    void anUnauthenticatedCaller_mayDoNeither() {
        assertThat(FeesPermissions.callerMaySetFees()).isFalse();
        assertThat(FeesPermissions.callerSeesFeeAuthors()).isFalse();
    }
}
