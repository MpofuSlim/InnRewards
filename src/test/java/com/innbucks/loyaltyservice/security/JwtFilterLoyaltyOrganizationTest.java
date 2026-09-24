package com.innbucks.loyaltyservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ONE rule that makes a caller a merchant admin in loyalty since
 * ownership moved to organizations (V51): the session's {@code orgId}, when the
 * caller is that organization's OWNER or ADMIN and it holds the loyalty product.
 * {@link JwtFilter} grants {@code ROLE_MERCHANT_ADMIN} and sets
 * {@link CallerDetails#organizationId()} from exactly this; the role in the
 * token's roles claim is not honoured on its own.
 *
 * <p>Every part is required, and each case below is a real account shape:
 * STAFF (a cashier added to the business), a marketplace-only business, a
 * session that has not chosen among several organizations, and a token minted
 * before organizations existed.
 */
class JwtFilterLoyaltyOrganizationTest {

    private static final UUID ORG = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");

    @Test
    @DisplayName("the OWNER of an organization with loyalty acts for it")
    void owner_withLoyalty_actsForTheOrganization() {
        assertThat(JwtFilter.loyaltyOrganizationOf(ORG, "OWNER", List.of("loyalty", "marketplace"))).isEqualTo(ORG);
    }

    @Test
    @DisplayName("an ADMIN colleague acts for it too — no platform role needed")
    void admin_withLoyalty_actsForTheOrganization() {
        assertThat(JwtFilter.loyaltyOrganizationOf(ORG, "ADMIN", List.of("loyalty"))).isEqualTo(ORG);
    }

    @Test
    @DisplayName("STAFF does not run the business, so gets nothing")
    void staff_getsNothing() {
        assertThat(JwtFilter.loyaltyOrganizationOf(ORG, "STAFF", List.of("loyalty"))).isNull();
    }

    @Test
    @DisplayName("a business without the loyalty product gets nothing, however senior the caller")
    void organizationWithoutLoyalty_getsNothing() {
        assertThat(JwtFilter.loyaltyOrganizationOf(ORG, "OWNER", List.of("marketplace", "ticketing"))).isNull();
        assertThat(JwtFilter.loyaltyOrganizationOf(ORG, "OWNER", List.of())).isNull();
    }

    @Test
    @DisplayName("a session with no organization chosen, or a pre-V39 token, gets nothing")
    void noOrganizationClaims_getsNothing() {
        assertThat(JwtFilter.loyaltyOrganizationOf(null, "OWNER", List.of("loyalty"))).isNull();
        assertThat(JwtFilter.loyaltyOrganizationOf(ORG, null, List.of("loyalty"))).isNull();
        assertThat(JwtFilter.loyaltyOrganizationOf(ORG, "OWNER", null)).isNull();
    }

    @Test
    @DisplayName("the product match is exact — user-service mints lowercase")
    void productMatchIsExact() {
        assertThat(JwtFilter.loyaltyOrganizationOf(ORG, "OWNER", List.of("LOYALTY"))).isNull();
        assertThat(JwtFilter.loyaltyOrganizationOf(ORG, "owner", List.of("loyalty"))).isNull();
    }
}
