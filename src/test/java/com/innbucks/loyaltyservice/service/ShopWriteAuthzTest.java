package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * Who may create, rename, activate or deactivate a shop. These writes used to
 * check only that the merchant / shop was in the tenant, so a SHOP_ADMIN or a
 * MERCHANT_ADMIN could add outlets to, rename, or switch off another merchant's
 * shop in the same tenant — and a deactivated shop refuses guest checkout.
 *
 * <p>Uses the real {@link MerchantAuthz} over mocked repositories, so the
 * ownership rule itself is exercised rather than assumed.
 */
class ShopWriteAuthzTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();

    private final ShopRepository shops = mock(ShopRepository.class);
    private final MerchantRepository merchantRepo = mock(MerchantRepository.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final ShopService service = new ShopService(shops, merchants,
            new MerchantAuthz(merchantRepo, shops), mock(PlatformTransactionManager.class));

    private final Merchant ours = merchant(ORG);
    private final Merchant theirs = merchant(OTHER_ORG);
    private final Shop ownShop = shop(ours, "Pizza Inn Avondale");
    private final Shop siblingShop = shop(ours, "Pizza Inn Borrowdale");
    private final Shop competitorShop = shop(theirs, "Chicken Inn Westgate");

    @BeforeEach
    void stubLookups() {
        when(shops.save(any(Shop.class))).thenAnswer(inv -> inv.getArgument(0));
        when(merchants.requireMerchant(TENANT, ours.getId())).thenReturn(ours);
        when(merchants.requireMerchant(TENANT, theirs.getId())).thenReturn(theirs);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---- create ----

    @Test
    void aShopAdmin_cannotAddAShopToAnotherMerchant() {
        asShopAdmin(ownShop);

        assertThatThrownBy(() -> service.create(TENANT, new Dtos.ShopRequest(theirs.getId(), "Pop-up", null)))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER");
        verify(shops, never()).save(any(Shop.class));
    }

    @Test
    void aShopAdmin_canAddAShopToItsOwnMerchant() {
        asShopAdmin(ownShop);

        Dtos.ShopResponse created = service.create(TENANT, new Dtos.ShopRequest(ours.getId(), "Pizza Inn Sam Levy", null));

        assertThat(created.merchantId()).isEqualTo(ours.getId());
    }

    @Test
    void aMerchantAdmin_canOnlyAddShopsToItsOwnOrganizationsMerchants() {
        asMerchantAdmin(ORG);
        assertThat(service.create(TENANT, new Dtos.ShopRequest(ours.getId(), "Pizza Inn Eastgate", null))
                .merchantId()).isEqualTo(ours.getId());

        assertThatThrownBy(() -> service.create(TENANT, new Dtos.ShopRequest(theirs.getId(), "Pop-up", null)))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER");
    }

    // ---- update / activate / deactivate ----

    @Test
    void aShopAdmin_cannotDeactivateASiblingOutlet() {
        asShopAdmin(ownShop);

        assertThatThrownBy(() -> service.setActive(TENANT, siblingShop.getId(), false))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_SHOP_MEMBER");
        assertThat(siblingShop.getStatus()).isEqualTo(Shop.Status.ACTIVE);
    }

    @Test
    void aShopAdmin_canDeactivateAndReactivateItsOwnShop() {
        asShopAdmin(ownShop);

        service.setActive(TENANT, ownShop.getId(), false);
        assertThat(ownShop.getStatus()).isEqualTo(Shop.Status.INACTIVE);
        service.setActive(TENANT, ownShop.getId(), true);
        assertThat(ownShop.getStatus()).isEqualTo(Shop.Status.ACTIVE);
    }

    @Test
    void aMerchantAdmin_cannotRenameACompetitorsShop() {
        asMerchantAdmin(ORG);

        assertThatThrownBy(() -> service.update(TENANT, competitorShop.getId(),
                new Dtos.ShopRequest(theirs.getId(), "Closed", null)))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER");
        assertThat(competitorShop.getName()).isEqualTo("Chicken Inn Westgate");
    }

    @Test
    void aMerchantAdmin_canRenameItsOwnShop() {
        asMerchantAdmin(ORG);

        service.update(TENANT, siblingShop.getId(), new Dtos.ShopRequest(ours.getId(), "Pizza Inn Borrowdale Village", null));

        assertThat(siblingShop.getName()).isEqualTo("Pizza Inn Borrowdale Village");
    }

    @Test
    void aTenantAdmin_keepsTenantWideReach() {
        as("ROLE_TENANT_ADMIN", null, null, null);

        service.setActive(TENANT, competitorShop.getId(), false);

        assertThat(competitorShop.getStatus()).isEqualTo(Shop.Status.INACTIVE);
    }

    // ---- bulk upload ----

    @Test
    void aMerchantAdmin_cannotBulkUploadShopsIntoAnotherMerchant() {
        asMerchantAdmin(ORG);
        InputStream csv = new ByteArrayInputStream("name,address\nPop-up,Somewhere\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.bulkUploadFromCsv(TENANT, theirs.getId(), csv))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER");
        verify(shops, never()).save(any(Shop.class));
    }

    // ---- fixtures ----

    private Merchant merchant(UUID organizationId) {
        Merchant m = new Merchant();
        m.setId(UUID.randomUUID());
        m.setTenantId(TENANT);
        m.setOrganizationId(organizationId);
        m.setName("Merchant");
        m.setCurrency("USD");
        m.setStatus(Merchant.Status.ACTIVE);
        when(merchantRepo.findById(m.getId())).thenReturn(Optional.of(m));
        return m;
    }

    private Shop shop(Merchant m, String name) {
        Shop s = new Shop();
        s.setId(UUID.randomUUID());
        s.setTenantId(TENANT);
        s.setMerchantId(m.getId());
        s.setName(name);
        s.setStatus(Shop.Status.ACTIVE);
        when(shops.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    private static void asShopAdmin(Shop s) {
        as("ROLE_SHOP_ADMIN", s.getMerchantId(), s.getId(), null);
    }

    private static void asMerchantAdmin(UUID organizationId) {
        as("ROLE_MERCHANT_ADMIN", null, null, organizationId);
    }

    private static void as(String role, UUID merchantId, UUID shopId, UUID organizationId) {
        var auth = new UsernamePasswordAuthenticationToken(
                "caller@test.local", null, List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(new CallerDetails(merchantId, shopId, null, UUID.randomUUID(), organizationId));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
