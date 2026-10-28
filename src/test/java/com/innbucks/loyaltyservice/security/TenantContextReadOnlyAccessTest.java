package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.TenantCachedLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Read-only platform access ({@code loyalty-reports:read}) is exempt from tenant
 * membership on a GET or HEAD, and on nothing else.
 *
 * <p>The write half is the one that matters: a write endpoint that ever let the
 * permission through its {@code @PreAuthorize} must still meet the membership
 * check here. Nobody in this class is a member of anything.
 */
class TenantContextReadOnlyAccessTest {

    private static final UUID TENANT_ID = UUID.fromString("0a571c1c-7c75-4000-a000-0000000000a1");

    private MockHttpServletRequest request;
    private TenantContext context;
    private TenantCachedLookup lookup;

    @BeforeEach
    void setUp() {
        TenantRepository tenants = mock(TenantRepository.class);
        lookup = mock(TenantCachedLookup.class);
        request = new MockHttpServletRequest();
        context = new TenantContext(tenants, lookup, request);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT_ID);
        tenant.setCode("zw-other");
        tenant.setName("Another business's program");
        when(lookup.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(lookup.isMember(any(), any())).thenReturn(false);
        when(lookup.isMemberByUserId(any(), any())).thenReturn(false);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken("dev@innbucks.co.zw", null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(null, null, null, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void asReader() {
        authenticate("ROLE_PRODUCT_OFFICER", ReportsPermissions.READ);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "HEAD"})
    @DisplayName("a reader reads a tenant it is no member of, on a GET or HEAD")
    void reader_isAdmittedOnASafeMethod(String method) {
        asReader();
        request.setMethod(method);
        request.addHeader("X-Tenant-Id", TENANT_ID.toString());

        assertThat(context.requireTenantId()).isEqualTo(TENANT_ID);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("a reader still needs membership on a write")
    void reader_stillNeedsMembershipOnAWrite(String method) {
        asReader();
        request.setMethod(method);
        request.addHeader("X-Tenant-Id", TENANT_ID.toString());

        assertThatThrownBy(() -> context.requireTenantId())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the same token without the permission is refused on a GET")
    void sameRoleWithoutThePermission_isRefused() {
        authenticate("ROLE_PRODUCT_OFFICER");
        request.setMethod("GET");
        request.addHeader("X-Tenant-Id", TENANT_ID.toString());

        assertThatThrownBy(() -> context.requireTenantId())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("tenant RESOLUTION is unchanged: the header is still required")
    void reader_stillNeedsTheTenantHeader() {
        asReader();
        request.setMethod("GET");

        assertThatThrownBy(() -> context.requireTenantId())
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("MISSING_TENANT"));
    }

    @Test
    @DisplayName("and still validated: an unknown tenant is a 404, never a free pass")
    void reader_unknownTenantIsStillNotFound() {
        asReader();
        request.setMethod("GET");
        UUID unknown = UUID.randomUUID();
        when(lookup.findById(unknown)).thenReturn(Optional.empty());
        request.addHeader("X-Tenant-Id", unknown.toString());

        assertThatThrownBy(() -> context.requireTenantId())
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus().value()).isEqualTo(404));
    }
}
