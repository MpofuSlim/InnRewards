package com.innbucks.loyaltyservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** The one definition the scope helpers widen on, and who loses voucher codes. */
class ReportsPermissionsTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone", null, Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static void request(String method) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setMethod(method);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
    }

    @Test
    void theCodeIsCharacterIdenticalToUserServicesCatalog_andShapedLikeAPermission() {
        assertThat(ReportsPermissions.READ).isEqualTo("loyalty-reports:read");
        // JwtFilter drops a perms entry that does not match this shape, so a
        // code that failed it would reach no endpoint at all.
        assertThat(SupportPermissions.CODE_SHAPE.matcher(ReportsPermissions.READ).matches()).isTrue();
        assertThat(ReportsPermissions.HAS_READ).isEqualTo("hasAuthority('loyalty-reports:read')");
    }

    @Test
    void readsOnlyOnGetAndHead() {
        authenticate("ROLE_PRODUCT_OFFICER", ReportsPermissions.READ);
        for (String safe : new String[] {"GET", "HEAD"}) {
            request(safe);
            assertThat(ReportsPermissions.readsOnThisRequest()).as(safe).isTrue();
        }
        for (String write : new String[] {"POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"}) {
            request(write);
            assertThat(ReportsPermissions.readsOnThisRequest()).as(write).isFalse();
        }
    }

    @Test
    void outsideARequestItReadsNothing() {
        authenticate("ROLE_PRODUCT_OFFICER", ReportsPermissions.READ);

        assertThat(ReportsPermissions.readsOnThisRequest()).isFalse();
        assertThat(ReportsPermissions.readsOnThisRequest(null)).isFalse();
    }

    @Test
    void withoutThePermissionNothingWidens() {
        authenticate("ROLE_PRODUCT_OFFICER", "loyalty-support:read");
        request("GET");

        assertThat(ReportsPermissions.readsOnThisRequest()).isFalse();
        assertThat(ReportsPermissions.codesWithheld()).isFalse();
    }

    @Test
    void aReaderLosesVoucherCodes() {
        authenticate("ROLE_PRODUCT_OFFICER", ReportsPermissions.READ);

        assertThat(ReportsPermissions.codesWithheld()).isTrue();
    }

    @Test
    void superAdminKeepsCodes_thoughItsWildcardTokenCarriesTheReadCodeToo() {
        authenticate("ROLE_SUPER_ADMIN", ReportsPermissions.READ, "loyalty-support:read");

        assertThat(ReportsPermissions.codesWithheld()).isFalse();
    }

    @Test
    void aReaderWhoAlsoHoldsAMerchantRoleStillLosesEveryCode() {
        // Never minted by user-service (a PLATFORM code makes a staff role, and
        // staff belong to no business). Fails closed if it ever is.
        authenticate("ROLE_MERCHANT_ADMIN", ReportsPermissions.READ);

        assertThat(ReportsPermissions.codesWithheld()).isTrue();
    }
}
