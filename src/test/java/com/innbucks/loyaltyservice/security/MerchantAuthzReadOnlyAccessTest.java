package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MerchantAuthz} for read-only platform access ({@code loyalty-reports:read}):
 * every merchant and shop of the tenant on a GET, and every write-side check
 * exactly as before.
 */
class MerchantAuthzReadOnlyAccessTest {

    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final ShopRepository shops = mock(ShopRepository.class);
    private final MerchantAuthz authz = new MerchantAuthz(merchants, shops);

    private final UUID tenant = UUID.randomUUID();
    private final UUID otherTenant = UUID.randomUUID();
    private static final UUID SOMEONE_ELSES_ORG = UUID.fromString("9c2d4e6f-1a3b-4c5d-8e7f-0a1b2c3d4e61");

    private Merchant merchant;
    private Shop shop;

    @BeforeEach
    void setUp() {
        merchant = new Merchant();
        merchant.setId(UUID.randomUUID());
        merchant.setTenantId(tenant);
        merchant.setOrganizationId(SOMEONE_ELSES_ORG);
        when(merchants.findById(merchant.getId())).thenReturn(Optional.of(merchant));
        when(merchants.findByTenantId(tenant)).thenReturn(List.of(merchant));

        shop = new Shop();
        shop.setId(UUID.randomUUID());
        shop.setTenantId(tenant);
        shop.setMerchantId(merchant.getId());
        when(shops.findById(shop.getId())).thenReturn(Optional.of(shop));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void request(String method) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setMethod(method);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
    }

    private static void authenticate(String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken("dev@innbucks.co.zw", null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new CallerDetails(null, null, null, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void asReader() {
        authenticate("ROLE_PRODUCT_OFFICER", ReportsPermissions.READ);
    }

    @Test
    @DisplayName("on a GET a reader reads the whole tenant: readableMerchants is null")
    void reader_readsEveryMerchantOnAGet() {
        asReader();
        request("GET");

        assertThat(authz.readableMerchants(tenant)).isNull();
        assertThat(authz.callerMayRead(merchant)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("on a write the same reader reads nothing")
    void reader_readsNothingOnAWrite(String method) {
        asReader();
        request(method);

        assertThat(authz.readableMerchants(tenant)).isEmpty();
        assertThat(authz.callerMayRead(merchant)).isFalse();
    }

    @Test
    @DisplayName("outside a request (a job, a listener) the permission widens nothing")
    void reader_outsideARequest_readsNothing() {
        asReader();

        assertThat(authz.readableMerchants(tenant)).isEmpty();
    }

    @Test
    @DisplayName("the read twins admit a reader to any merchant and shop of the tenant on a GET")
    void readTwins_admitTheReaderOnAGet() {
        asReader();
        request("GET");

        assertThat(authz.requireCallerMayReadMerchant(tenant, merchant.getId())).isSameAs(merchant);
        assertThat(authz.requireCallerMayReadShop(tenant, shop.getId())).isSameAs(shop);
    }

    @Test
    @DisplayName("a merchant or shop in ANOTHER tenant is still absent (404), not readable")
    void readTwins_stayTenantScoped() {
        asReader();
        request("GET");

        assertThatThrownBy(() -> authz.requireCallerMayReadMerchant(otherTenant, merchant.getId()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> authz.requireCallerMayReadShop(otherTenant, shop.getId()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getStatus().value()).isEqualTo(404));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("on a write the read twins are the write-side check, which a reader fails")
    void readTwins_onAWrite_refuseTheReader(String method) {
        asReader();
        request(method);

        assertThatThrownBy(() -> authz.requireCallerMayReadMerchant(tenant, merchant.getId()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER"));
        assertThatThrownBy(() -> authz.requireCallerMayReadShop(tenant, shop.getId()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER"));
    }

    @Test
    @DisplayName("the write-side checks refuse a reader even on a GET")
    void writeSideChecks_refuseTheReaderWhateverTheMethod() {
        asReader();
        request("GET");

        assertThatThrownBy(() -> authz.requireCallerAdministersMerchant(tenant, merchant.getId()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER"));
        assertThatThrownBy(() -> authz.requireCallerAccessesShop(tenant, shop.getId()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER"));
    }

    @Test
    @DisplayName("the same token without the permission is a merchant admin of nothing")
    void withoutThePermission_nothingIsReadable() {
        authenticate("ROLE_PRODUCT_OFFICER");
        request("GET");

        assertThat(authz.readableMerchants(tenant)).isEmpty();
        assertThatThrownBy(() -> authz.requireCallerMayReadMerchant(tenant, merchant.getId()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER"));
    }
}
