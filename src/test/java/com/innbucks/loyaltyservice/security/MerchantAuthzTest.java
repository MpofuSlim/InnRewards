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
 * MERCHANT_ADMIN may act only on merchants they created (adminEmail match) and
 * shops belonging to those merchants.
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

    private void authenticate(String email, String role, UUID merchantClaim, UUID shopClaim) {
        var auth = new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.setDetails(new CallerDetails(merchantClaim, shopClaim, "+263770000000", UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Merchant merchant(UUID id, String adminEmail) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setTenantId(tenant);
        m.setAdminEmail(adminEmail);
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
        merchant(mId, "someone@else.test");
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
    void merchantAdmin_ownsMerchantsTheyCreated_byAdminEmail() {
        UUID mine = UUID.randomUUID();
        UUID sibling = UUID.randomUUID();
        merchant(mine, "boss@acme.test");
        merchant(sibling, "rival@other.test");
        authenticate("boss@acme.test", "MERCHANT_ADMIN", null, null);

        assertThat(authz.requireCallerAdministersMerchant(tenant, mine).getId()).isEqualTo(mine);
        assertForbidden(() -> authz.requireCallerAdministersMerchant(tenant, sibling), "NOT_MERCHANT_OWNER");
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
        merchant(ownedMerchant, "boss@acme.test");
        merchant(rivalMerchant, "rival@other.test");
        UUID ownShop = UUID.randomUUID();
        UUID rivalShop = UUID.randomUUID();
        shop(ownShop, ownedMerchant);
        shop(rivalShop, rivalMerchant);
        authenticate("boss@acme.test", "MERCHANT_ADMIN", null, null);

        assertThat(authz.requireCallerAccessesShop(tenant, ownShop).getId()).isEqualTo(ownShop);
        assertForbidden(() -> authz.requireCallerAccessesShop(tenant, rivalShop), "NOT_MERCHANT_OWNER");
    }
}
