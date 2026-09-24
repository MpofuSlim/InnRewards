package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Object-level authorization tests for {@link MerchantAuthz} (OWASP A01).
 *
 * <p>Pins the per-merchant / per-shop ownership model that closes the
 * cross-merchant and cross-shop IDOR: a SUPER_ADMIN operator may act on any
 * merchant/shop; SHOP staff are pinned to the merchant/shop in their JWT; a
 * MERCHANT_ADMIN may act only on merchants their ORGANIZATION owns and shops
 * belonging to those merchants. An email match grants nothing any more, and a
 * merchant no organization owns is reachable by SUPER_ADMIN only.
 */
class MerchantAuthzTest {

    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final ShopRepository shops = mock(ShopRepository.class);
    private final MerchantAuthz authz = new MerchantAuthz(merchants, shops);

    private final UUID tenant = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static final UUID ACME = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");
    private static final UUID RIVAL = UUID.fromString("9c2d4e6f-1a3b-4c5d-8e7f-0a1b2c3d4e60");

    private void authenticate(String email, String role, UUID merchantClaim, UUID shopClaim) {
        authenticate(email, role, merchantClaim, shopClaim, null);
    }

    /** {@code organization} is the loyalty organization JwtFilter resolved for the caller. */
    private void authenticate(String email, String role, UUID merchantClaim, UUID shopClaim, UUID organization) {
        var auth = new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.setDetails(new CallerDetails(merchantClaim, shopClaim, "+263770000000", UUID.randomUUID(),
                organization));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Merchant merchant(UUID id, UUID organizationId) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setTenantId(tenant);
        m.setOrganizationId(organizationId);
        when(merchants.findById(id)).thenReturn(Optional.of(m));
        return m;
    }

    private Shop shop(UUID id, UUID merchantId) {
        Shop s = new Shop();
        s.setId(id);
        s.setTenantId(tenant);
        s.setMerchantId(merchantId);
        when(shops.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> {
                    LoyaltyException le = (LoyaltyException) ex;
                    assertThat(le.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(le.getCode()).isEqualTo(code);
                });
    }

    // ---- merchant ----

    @Test
    void superAdmin_mayAdministerAnyMerchant() {
        UUID mId = UUID.randomUUID();
        merchant(mId, RIVAL);
        authenticate("ops@platform.test", "SUPER_ADMIN", null, null);
        assertThat(authz.requireCallerAdministersMerchant(tenant, mId).getId()).isEqualTo(mId);
    }

    @Test
    void shopAdmin_pinnedToOwnMerchant() {
        UUID mine = UUID.randomUUID();
        UUID sibling = UUID.randomUUID();
        merchant(mine, null);
        merchant(sibling, null);
        authenticate("shopadmin@test", "SHOP_ADMIN", /*merchantClaim*/ mine, UUID.randomUUID());

        assertThat(authz.requireCallerAdministersMerchant(tenant, mine).getId()).isEqualTo(mine);
        assertForbidden(() -> authz.requireCallerAdministersMerchant(tenant, sibling), "NOT_MERCHANT_OWNER");
    }

    @Test
    void merchantAdmin_ownsEveryMerchantOfTheirOrganization_andNoOneElses() {
        UUID chickenInn = UUID.randomUUID();
        UUID pizzaInn = UUID.randomUUID();
        UUID rivals = UUID.randomUUID();
        merchant(chickenInn, ACME);
        merchant(pizzaInn, ACME);
        merchant(rivals, RIVAL);
        authenticate("boss@acme.test", "MERCHANT_ADMIN", null, null, ACME);

        assertThat(authz.requireCallerAdministersMerchant(tenant, chickenInn).getId()).isEqualTo(chickenInn);
        assertThat(authz.requireCallerAdministersMerchant(tenant, pizzaInn).getId()).isEqualTo(pizzaInn);
        assertForbidden(() -> authz.requireCallerAdministersMerchant(tenant, rivals), "NOT_MERCHANT_OWNER");
    }

    @Test
    void merchantAdmin_withNoLoyaltyOrganization_ownsNothing() {
        // What JwtFilter produces for a STAFF member, a business without the
        // loyalty product, or a session that has not chosen an organization.
        UUID mId = UUID.randomUUID();
        merchant(mId, ACME);
        authenticate("boss@acme.test", "MERCHANT_ADMIN", null, null, null);

        assertForbidden(() -> authz.requireCallerAdministersMerchant(tenant, mId), "NOT_MERCHANT_OWNER");
    }

    @Test
    void anUnownedMerchant_isReachableBySuperAdminOnly_neverByAnOrganization() {
        UUID unowned = UUID.randomUUID();
        merchant(unowned, null);
        authenticate("boss@acme.test", "MERCHANT_ADMIN", null, null, ACME);

        assertForbidden(() -> authz.requireCallerAdministersMerchant(tenant, unowned), "NOT_MERCHANT_OWNER");
    }

    @Test
    void merchantInAnotherTenant_isNotFound_notForbidden() {
        UUID mId = UUID.randomUUID();
        Merchant m = new Merchant();
        m.setId(mId);
        m.setTenantId(UUID.randomUUID()); // different tenant
        when(merchants.findById(mId)).thenReturn(Optional.of(m));
        authenticate("ops@platform.test", "SUPER_ADMIN", null, null);

        assertThatThrownBy(() -> authz.requireCallerAdministersMerchant(tenant, mId))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- shop ----

    @Test
    void shopUser_pinnedToOwnShop() {
        UUID myShop = UUID.randomUUID();
        UUID otherShop = UUID.randomUUID();
        shop(myShop, UUID.randomUUID());
        shop(otherShop, UUID.randomUUID());
        authenticate("till@test", "SHOP_USER", UUID.randomUUID(), /*shopClaim*/ myShop);

        assertThat(authz.requireCallerAccessesShop(tenant, myShop).getId()).isEqualTo(myShop);
        assertForbidden(() -> authz.requireCallerAccessesShop(tenant, otherShop), "NOT_SHOP_MEMBER");
    }

    @Test
    void merchantAdmin_accessesShopsOfOwnedMerchantOnly() {
        UUID ownedMerchant = UUID.randomUUID();
        UUID rivalMerchant = UUID.randomUUID();
        merchant(ownedMerchant, ACME);
        merchant(rivalMerchant, RIVAL);
        UUID ownShop = UUID.randomUUID();
        UUID rivalShop = UUID.randomUUID();
        shop(ownShop, ownedMerchant);
        shop(rivalShop, rivalMerchant);
        authenticate("boss@acme.test", "MERCHANT_ADMIN", null, null, ACME);

        assertThat(authz.requireCallerAccessesShop(tenant, ownShop).getId()).isEqualTo(ownShop);
        assertForbidden(() -> authz.requireCallerAccessesShop(tenant, rivalShop), "NOT_MERCHANT_OWNER");
    }
}
