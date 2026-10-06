package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantMemberRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.TenantCachedLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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
 * SHOP staff are tenant members through the MERCHANT in their token. Before
 * this a cashier could reach no tenant-scoped endpoint: {@code tenant_members}
 * has no writer that could ever add one.
 *
 * <p>The lookup is the real {@link TenantCachedLookup} over mocked
 * repositories, so what is pinned is the query actually asked — "is THIS
 * merchant in THIS tenant" — not a stub's say-so.
 */
class TenantContextShopStaffMembershipTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWN_MERCHANT = UUID.randomUUID();
    private static final UUID FOREIGN_MERCHANT = UUID.randomUUID(); // a merchant of another tenant

    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final TenantMemberRepository members = mock(TenantMemberRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantCachedLookup lookup = new TenantCachedLookup(tenants, members, merchants);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final TenantContext context = new TenantContext(tenants, lookup, request);

    @BeforeEach
    void setUp() {
        Tenant tenant = new Tenant();
        tenant.setId(TENANT_ID);
        tenant.setCode("zw-main");
        tenant.setName("ZW");
        when(tenants.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        request.addHeader("X-Tenant-Id", TENANT_ID.toString());

        when(merchants.existsByIdAndTenantId(OWN_MERCHANT, TENANT_ID)).thenReturn(true);
        when(merchants.existsByIdAndTenantId(FOREIGN_MERCHANT, TENANT_ID)).thenReturn(false);
        // Nobody holds a tenant_members row unless a test says so.
        when(members.existsByTenantIdAndEmail(any(), any())).thenReturn(false);
        when(members.existsByTenantIdAndUserId(any(), any())).thenReturn(false);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCashier_isAMemberOfItsOwnMerchantsTenant_withNoMembershipRow() {
        for (String role : List.of("ROLE_SHOP_USER", "ROLE_SHOP_ADMIN")) {
            authenticate(role, OWN_MERCHANT);
            assertThat(new TenantContext(tenants, lookup, request).requireTenantId()).as(role).isEqualTo(TENANT_ID);
        }
        verify(members, never()).existsByTenantIdAndUserId(any(), any());
    }

    @Test
    void aCashierWhoseMerchantIsInAnotherTenant_isRefused() {
        authenticate("ROLE_SHOP_USER", FOREIGN_MERCHANT);

        assertThatThrownBy(context::requireTenantId)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not a member");
    }

    @Test
    void aCashierWithNoMerchantClaim_isRefused_andNoMerchantIsAsked() {
        authenticate("ROLE_SHOP_USER", null);

        assertThatThrownBy(context::requireTenantId).isInstanceOf(AccessDeniedException.class);
        verify(merchants, never()).existsByIdAndTenantId(any(), any());
    }

    @Test
    void aMerchantClaimOnANonShopRole_isNotAWayIn() {
        // Only shop staff are admitted by merchant; any other token shape
        // carrying the claim falls through to tenant_members.
        for (String role : List.of("ROLE_MERCHANT_ADMIN", "ROLE_TENANT_ADMIN", "ROLE_EVENT_ORGANIZER")) {
            authenticate(role, OWN_MERCHANT);
            assertThatThrownBy(() -> new TenantContext(tenants, lookup, request).requireTenantId())
                    .as(role).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void theTenantMembersPath_isUnchanged() {
        // A shop-staff token whose merchant is elsewhere still gets in through a
        // membership row — the old path is untouched, just checked after.
        authenticate("ROLE_SHOP_USER", FOREIGN_MERCHANT);
        when(members.existsByTenantIdAndEmail(TENANT_ID, "staff@test.local")).thenReturn(true);

        assertThat(context.requireTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void theMerchantLookupIsNeverCached() throws Exception {
        // A stale "yes" would keep a moved merchant's staff inside the old program.
        assertThat(TenantCachedLookup.class.getMethod("merchantBelongsTo", UUID.class, UUID.class)
                .getAnnotation(Cacheable.class)).isNull();
    }

    private static void authenticate(String role, UUID merchantId) {
        var auth = new UsernamePasswordAuthenticationToken("staff@test.local", null,
                List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(new CallerDetails(merchantId, UUID.randomUUID(), "+263771234567", UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
