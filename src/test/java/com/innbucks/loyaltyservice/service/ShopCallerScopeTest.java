package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shop reads are scoped to the caller's token. A staff account has exactly one
 * shop ({@code shopId} claim) and one merchant ({@code merchantId} claim); the
 * shop list used to ignore both and return the whole tenant, so a shop admin's
 * picker offered other merchants' outlets that every shop-scoped write then
 * refused, and a cashier could not read their own shop at all.
 */
class ShopCallerScopeTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT = UUID.randomUUID();
    private static final Pageable PAGE = PageRequest.of(0, 20);

    private final ShopRepository shops = mock(ShopRepository.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final ShopService service = new ShopService(shops, merchants,
            mock(com.innbucks.loyaltyservice.security.MerchantAuthz.class), mock(PlatformTransactionManager.class));

    private final Shop own = shop(MERCHANT, "Pizza Inn Avondale");
    private final Shop sibling = shop(MERCHANT, "Pizza Inn Borrowdale");
    private final Shop competitor = shop(OTHER_MERCHANT, "Chicken Inn Westgate");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---- GET /loyalty/shops ----

    @Test
    void aCashier_seesOnlyTheirOwnShop() {
        as("ROLE_SHOP_USER", MERCHANT, own.getId());
        when(shops.findById(own.getId())).thenReturn(Optional.of(own));

        Page<Dtos.ShopResponse> page = service.list(TENANT, null, PAGE);

        assertThat(page.getContent()).extracting(Dtos.ShopResponse::id).containsExactly(own.getId());
        verify(shops, never()).findByTenantId(any(), any());
    }

    @Test
    void aShopAdmin_seesOnlyTheirOwnShop_notTheWholeTenant() {
        as("ROLE_SHOP_ADMIN", MERCHANT, own.getId());
        when(shops.findById(own.getId())).thenReturn(Optional.of(own));

        assertThat(service.list(TENANT, null, PAGE).getContent())
                .extracting(Dtos.ShopResponse::id).containsExactly(own.getId());
    }

    @Test
    void aMerchantFilterOutsideTheCallersShop_isAnEmptyPage() {
        as("ROLE_SHOP_USER", MERCHANT, own.getId());
        when(shops.findById(own.getId())).thenReturn(Optional.of(own));

        assertThat(service.list(TENANT, OTHER_MERCHANT, PAGE).getContent()).isEmpty();
    }

    @Test
    void aShopFromAnotherTenant_isNeverReturned() {
        Shop elsewhere = shop(MERCHANT, "Elsewhere");
        elsewhere.setTenantId(UUID.randomUUID());
        as("ROLE_SHOP_USER", MERCHANT, elsewhere.getId());
        when(shops.findById(elsewhere.getId())).thenReturn(Optional.of(elsewhere));

        assertThat(service.list(TENANT, null, PAGE).getContent()).isEmpty();
    }

    @Test
    void aTokenNamingOnlyAMerchant_seesThatMerchantsShops() {
        as("ROLE_SHOP_ADMIN", MERCHANT, null);
        when(shops.findByTenantIdAndMerchantId(eq(TENANT), eq(MERCHANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(own, sibling), PAGE, 2));

        assertThat(service.list(TENANT, null, PAGE).getContent())
                .extracting(Dtos.ShopResponse::id).containsExactly(own.getId(), sibling.getId());
        verify(shops, never()).findByTenantId(any(), any());
    }

    @Test
    void aTokenNamingOnlyAMerchant_filteringAnotherMerchant_getsNothing() {
        as("ROLE_SHOP_ADMIN", MERCHANT, null);

        assertThat(service.list(TENANT, OTHER_MERCHANT, PAGE).getContent()).isEmpty();
        verify(shops, never()).findByTenantIdAndMerchantId(any(), any(), any(Pageable.class));
    }

    @Test
    void aMerchantAdmin_andASuperAdmin_areUnchanged() {
        when(shops.findByTenantId(eq(TENANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(own, sibling, competitor), PAGE, 3));

        as("ROLE_MERCHANT_ADMIN", null, null);
        assertThat(service.list(TENANT, null, PAGE).getContent()).hasSize(3);

        // A SUPER_ADMIN is never pinned, even if a claim is present.
        as("ROLE_SUPER_ADMIN", MERCHANT, own.getId());
        assertThat(service.list(TENANT, null, PAGE).getContent()).hasSize(3);
    }

    // ---- GET /loyalty/shops/{id} ----

    @Test
    void aCashier_canReadTheirOwnShop() {
        as("ROLE_SHOP_USER", MERCHANT, own.getId());
        when(shops.findById(own.getId())).thenReturn(Optional.of(own));

        assertThat(service.getForCaller(TENANT, own.getId()).name()).isEqualTo("Pizza Inn Avondale");
    }

    @Test
    void aCashier_cannotReadASiblingOutlet() {
        as("ROLE_SHOP_USER", MERCHANT, own.getId());
        when(shops.findById(sibling.getId())).thenReturn(Optional.of(sibling));

        assertThatThrownBy(() -> service.getForCaller(TENANT, sibling.getId()))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_SHOP_MEMBER");
    }

    @Test
    void aTokenNamingOnlyAMerchant_cannotReadAnotherMerchantsShop() {
        as("ROLE_SHOP_ADMIN", MERCHANT, null);
        when(shops.findById(competitor.getId())).thenReturn(Optional.of(competitor));

        assertThatThrownBy(() -> service.getForCaller(TENANT, competitor.getId()))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER");
    }

    @Test
    void theUnscopedGet_staysUnscoped_forGuestCheckout() {
        // Guest checkout loads the shop through get() and applies its own
        // SHOP_NOT_OWNED / NOT_SHOP_MEMBER checks; scoping get() itself would
        // change those codes.
        as("ROLE_SHOP_USER", MERCHANT, own.getId());
        when(shops.findById(competitor.getId())).thenReturn(Optional.of(competitor));

        assertThat(service.get(TENANT, competitor.getId()).id()).isEqualTo(competitor.getId());
    }

    // ---- GET /loyalty/shops/by-merchant/{merchantId} ----

    @Test
    void byMerchant_narrowsToTheCallersShop() {
        as("ROLE_SHOP_ADMIN", MERCHANT, own.getId());
        when(shops.findByTenantIdAndMerchantId(TENANT, MERCHANT)).thenReturn(List.of(own, sibling));

        assertThat(service.listForMerchant(TENANT, MERCHANT))
                .extracting(Dtos.ShopResponse::id).containsExactly(own.getId());
    }

    @Test
    void byMerchant_forAnotherMerchant_isRefused() {
        as("ROLE_SHOP_ADMIN", MERCHANT, own.getId());

        assertThatThrownBy(() -> service.listForMerchant(TENANT, OTHER_MERCHANT))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER");
        verify(shops, never()).findByTenantIdAndMerchantId(any(), any());
    }

    // ---- fixtures ----

    private static Shop shop(UUID merchantId, String name) {
        Shop s = new Shop();
        s.setId(UUID.randomUUID());
        s.setTenantId(TENANT);
        s.setMerchantId(merchantId);
        s.setName(name);
        return s;
    }

    private static void as(String role, UUID merchantId, UUID shopId) {
        var auth = new UsernamePasswordAuthenticationToken(
                "caller@test.local", null, List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(new CallerDetails(merchantId, shopId, null, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
