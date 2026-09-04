package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.TenantCachedLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may pass the tenant membership check.
 *
 * <p>The exemption under test exists because {@code tenant_members} has no
 * writer that can ever admit a customer — no {@code POST /{id}/members} exists,
 * and the two writers attach a tenant's CREATOR and its owner email. So for a
 * customer the check was not a gate, it was a wall, and it is why the app has
 * only ever reached loyalty through {@code /loyalty/public/**}.
 *
 * <p>What must not change is staff behaviour, so most of these cases are about
 * callers who still have to be members.
 */
class TenantContextCustomerExemptionTest {

    private static final UUID TENANT_ID = UUID.fromString("0a571c1c-7c75-4000-a000-000000000001");

    private TenantCachedLookup lookup;
    private MockHttpServletRequest request;
    private TenantContext context;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        TenantRepository tenants = mock(TenantRepository.class);
        lookup = mock(TenantCachedLookup.class);
        request = new MockHttpServletRequest();
        context = new TenantContext(tenants, lookup, request);

        tenant = new Tenant();
        tenant.setId(TENANT_ID);
        tenant.setCode("zw-main");
        tenant.setName("ZW");
        when(lookup.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        request.addHeader("X-Tenant-Id", TENANT_ID.toString());

        // Nobody in this class is a member. That is the point: every pass below
        // is a pass WITHOUT a membership row, and every refusal is one that
        // still needs it.
        when(lookup.isMember(any(), any())).thenReturn(false);
        when(lookup.isMemberByUserId(any(), any())).thenReturn(false);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String name, String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken(name, null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(null, null, "+263771234567", UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("a plain customer resolves the tenant without a membership row")
    void plainCustomer_isAdmitted() {
        authenticate("customer@test.local", "ROLE_CUSTOMER");

        assertThat(context.requireTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("the exemption does not even consult the membership tables")
    void plainCustomer_shortCircuitsTheLookup() {
        // Not just cosmetic: this runs on every customer spend, and the lookup
        // is a cache miss plus two queries for a row that cannot exist.
        authenticate("customer@test.local", "ROLE_CUSTOMER");

        context.requireTenantId();

        verify(lookup, never()).isMember(any(), any());
        verify(lookup, never()).isMemberByUserId(any(), any());
    }

    @Test
    @DisplayName("a VERIFIED, tiered customer is still a plain customer")
    void customerWithNonRoleAuthorities_isStillExempt() {
        // JwtFilter grants SERVICE_*, TIER_* and VERIFIED alongside ROLE_*.
        // Those describe the token, not a role, so they must not disqualify —
        // otherwise the exemption would fail for exactly the fully-onboarded
        // customers it exists for.
        authenticate("customer@test.local", "ROLE_CUSTOMER", "TIER_3", "VERIFIED");

        assertThat(context.requireTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("SUPER_ADMIN keeps its own exemption")
    void superAdmin_isAdmitted() {
        authenticate("root@test.local", "ROLE_SUPER_ADMIN");

        assertThat(context.requireTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("a MERCHANT_ADMIN without membership is still refused")
    void merchantAdmin_stillNeedsMembership() {
        authenticate("admin@test.local", "ROLE_MERCHANT_ADMIN");

        assertThatThrownBy(() -> context.requireTenantId())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not a member");
    }

    @Test
    @DisplayName("SHOP_USER, SHOP_ADMIN, EVENT_ORGANIZER, TENANT_ADMIN all still need membership")
    void otherStaffRoles_stillNeedMembership() {
        for (String role : List.of("ROLE_SHOP_USER", "ROLE_SHOP_ADMIN",
                "ROLE_EVENT_ORGANIZER", "ROLE_TENANT_ADMIN", "ROLE_PLATFORM_ADMIN")) {
            SecurityContextHolder.clearContext();
            context = new TenantContext(mock(TenantRepository.class), lookup, request);
            authenticate("staff@test.local", role);

            assertThatThrownBy(() -> context.requireTenantId())
                    .as("%s must still require membership", role)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    @DisplayName("a CUSTOMER token that also carries a staff role is NOT exempt")
    void mixedRoleToken_isNotExempt() {
        // The exemption must never become a way for a staff caller to reach a
        // tenant they are not a member of, simply by also holding CUSTOMER.
        authenticate("cashier@test.local", "ROLE_CUSTOMER", "ROLE_SHOP_ADMIN");

        assertThatThrownBy(() -> context.requireTenantId())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not a member");
    }

    @Test
    @DisplayName("an unknown future role does not inherit the exemption")
    void unknownRoleAlongsideCustomer_isNotExempt() {
        // The predicate is role-set EQUALITY, not a deny-list of today's staff
        // roles. A role added later fails closed and has to be considered.
        authenticate("someone@test.local", "ROLE_CUSTOMER", "ROLE_FUTURE_ROLE");

        assertThatThrownBy(() -> context.requireTenantId())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a caller with no role at all is still refused")
    void noRoles_isRefused() {
        authenticate("nobody@test.local", "VERIFIED");

        assertThatThrownBy(() -> context.requireTenantId())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an unauthenticated caller is still refused")
    void unauthenticated_isRefused() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> context.requireTenantId())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Authentication required");
    }

    @Test
    @DisplayName("the exemption does not skip tenant RESOLUTION — a missing header still 400s")
    void exemptCustomer_stillNeedsATenantHeader() {
        // Membership and resolution are separate. A customer is exempt from the
        // former, never the latter, or a customer request would act on no tenant
        // at all.
        MockHttpServletRequest noHeader = new MockHttpServletRequest();
        TenantContext ctx = new TenantContext(mock(TenantRepository.class), lookup, noHeader);
        authenticate("customer@test.local", "ROLE_CUSTOMER");

        assertThatThrownBy(ctx::requireTenantId)
                .isInstanceOf(com.innbucks.loyaltyservice.exception.LoyaltyException.class)
                .hasMessageContaining("X-Tenant-Id");
    }

    @Test
    @DisplayName("an exempt customer naming an unknown tenant still 404s")
    void exemptCustomer_stillNeedsARealTenant() {
        UUID unknown = UUID.randomUUID();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Tenant-Id", unknown.toString());
        when(lookup.findById(unknown)).thenReturn(Optional.empty());
        TenantContext ctx = new TenantContext(mock(TenantRepository.class), lookup, req);
        authenticate("customer@test.local", "ROLE_CUSTOMER");

        assertThatThrownBy(ctx::requireTenantId)
                .isInstanceOf(com.innbucks.loyaltyservice.exception.LoyaltyException.class)
                .hasMessageContaining("not found");
    }
}
