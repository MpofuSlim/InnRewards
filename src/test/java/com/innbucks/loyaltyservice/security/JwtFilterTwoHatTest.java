package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.repository.MerchantRepository;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * One hat per token. user-service mints a shop-staff account's {@code merchantId}
 * / {@code shopId} next to whatever organization its session acts for, and
 * {@link MerchantAuthz} honours the pin before the organization. So a cashier
 * that ANY loyalty business made OWNER/ADMIN was a merchant admin pinned to
 * their EMPLOYER. The filter now grants the organization's merchant-admin hat to
 * a shop-scoped token only when that organization owns the pinned merchant.
 */
class JwtFilterTwoHatTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private static final UUID EMPLOYER = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID HOME_SHOP = UUID.fromString("5a000000-0000-4000-8000-000000000003");
    /** The organization that owns EMPLOYER. */
    private static final UUID EMPLOYER_ORG = UUID.fromString("0e000000-0000-4000-8000-000000000004");
    /** Some other business. */
    private static final UUID OTHER_ORG = UUID.fromString("0b000000-0000-4000-8000-000000000005");

    private MerchantRepository merchants;
    private SimpleMeterRegistry registry;
    private JwtFilter filter;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        RevokedTokenDenylist denylist = mock(RevokedTokenDenylist.class);
        when(denylist.isRevoked(anyString())).thenReturn(false);
        merchants = mock(MerchantRepository.class);
        when(merchants.findOrganizationIdById(EMPLOYER)).thenReturn(Optional.of(EMPLOYER_ORG));
        registry = new SimpleMeterRegistry();
        filter = new JwtFilter(jwtUtil, denylist, mock(TokenVersionStore.class),
                new ShopPinOrganizationCheck(merchants, registry));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static JwtBuilder token(String... roles) {
        return Jwts.builder()
                .subject("cashier@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .claim("roles", Arrays.asList(roles))
                .claim("services", List.of("loyalty"));
    }

    private static JwtBuilder pinned(JwtBuilder b) {
        return b.claim("merchantId", EMPLOYER.toString()).claim("shopId", HOME_SHOP.toString());
    }

    private static JwtBuilder org(JwtBuilder b, UUID organizationId, String orgRole) {
        return b.claim("orgId", organizationId.toString()).claim("orgRole", orgRole)
                .claim("products", List.of("loyalty"));
    }

    /** Runs the filter; returns the response so a test can prove nobody was signed out. */
    private MockHttpServletResponse run(JwtBuilder b) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/loyalty/shops");
        req.addHeader("Authorization", "Bearer " + b.signWith(KEY, Jwts.SIG.HS256).compact());
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private static Set<String> authorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).as("the caller is still authenticated").isNotNull();
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private static CallerDetails details() {
        return (CallerDetails) SecurityContextHolder.getContext().getAuthentication().getDetails();
    }

    private double count(String outcome) {
        return registry.get(ShopPinOrganizationCheck.METRIC).tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("a cashier another business made its ADMIN is a cashier here, and nothing more")
    void cashier_adminOfAnotherBusiness_keepsOnlyTheShopHat() throws Exception {
        run(org(pinned(token("SHOP_USER")), OTHER_ORG, "ADMIN"));

        assertThat(authorities()).contains("ROLE_SHOP_USER").doesNotContain("ROLE_MERCHANT_ADMIN");
        assertThat(details().organizationId()).isNull();
        // The till still works: the pin is untouched.
        assertThat(details().merchantId()).isEqualTo(EMPLOYER);
        assertThat(details().shopId()).isEqualTo(HOME_SHOP);
        assertThat(count("withheld")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a shop admin who also OWNS another business keeps its shop admin hat only")
    void shopAdmin_ownerOfAnotherBusiness_keepsOnlyTheShopHat() throws Exception {
        run(org(pinned(token("SHOP_ADMIN", "MERCHANT_ADMIN")), OTHER_ORG, "OWNER"));

        assertThat(authorities()).contains("ROLE_SHOP_ADMIN").doesNotContain("ROLE_MERCHANT_ADMIN");
        assertThat(details().organizationId()).isNull();
        assertThat(details().merchantId()).isEqualTo(EMPLOYER);
    }

    @Test
    @DisplayName("shop staff the employer promoted in its OWN organization wear both hats, as before")
    void promotedByTheEmployer_keepsMerchantAdmin() throws Exception {
        run(org(pinned(token("SHOP_ADMIN")), EMPLOYER_ORG, "ADMIN"));

        assertThat(authorities()).contains("ROLE_SHOP_ADMIN", "ROLE_MERCHANT_ADMIN");
        assertThat(details().organizationId()).isEqualTo(EMPLOYER_ORG);
        assertThat(details().merchantId()).isEqualTo(EMPLOYER);
        assertThat(count("org_owns_pin")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a failed lookup keeps the shop hat and never signs the cashier out")
    void lookupFailure_failsClosed_withoutA401() throws Exception {
        when(merchants.findOrganizationIdById(any())).thenThrow(new DataAccessResourceFailureException("down"));

        MockHttpServletResponse res = run(org(pinned(token("SHOP_USER")), EMPLOYER_ORG, "OWNER"));

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(authorities()).contains("ROLE_SHOP_USER").doesNotContain("ROLE_MERCHANT_ADMIN");
        assertThat(details().organizationId()).isNull();
        assertThat(count("lookup_failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unknown pinned merchant is not owned by anyone")
    void unknownPinnedMerchant_isWithheld() throws Exception {
        when(merchants.findOrganizationIdById(EMPLOYER)).thenReturn(Optional.empty());

        run(org(pinned(token("SHOP_USER")), EMPLOYER_ORG, "OWNER"));

        assertThat(authorities()).doesNotContain("ROLE_MERCHANT_ADMIN");
        assertThat(details().organizationId()).isNull();
    }

    @Test
    @DisplayName("any part of the shop scope counts: a shop role with no pin, or a shop claim alone")
    void anyShopScope_withoutAPin_isWithheld_withoutALookup() throws Exception {
        run(org(token("SHOP_USER"), OTHER_ORG, "OWNER"));
        assertThat(authorities()).contains("ROLE_SHOP_USER").doesNotContain("ROLE_MERCHANT_ADMIN");
        SecurityContextHolder.clearContext();

        run(org(token("CUSTOMER").claim("shopId", HOME_SHOP.toString()), OTHER_ORG, "OWNER"));
        assertThat(authorities()).doesNotContain("ROLE_MERCHANT_ADMIN");
        assertThat(details().organizationId()).isNull();

        // No pin to look up: refused without asking the database.
        verifyNoInteractions(merchants);
        assertThat(count("withheld")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("an organization admin with no shop scope is untouched and never checked")
    void plainOrganizationAdmin_isUnchanged() throws Exception {
        run(org(token("CUSTOMER"), OTHER_ORG, "ADMIN"));

        assertThat(authorities()).contains("ROLE_MERCHANT_ADMIN");
        assertThat(details().organizationId()).isEqualTo(OTHER_ORG);
        verifyNoInteractions(merchants);
        assertNeverChecked();
    }

    @Test
    @DisplayName("plain shop staff with no organization are untouched and never checked")
    void plainShopStaff_isUnchanged() throws Exception {
        run(pinned(token("SHOP_USER")));

        assertThat(authorities()).contains("ROLE_SHOP_USER").doesNotContain("ROLE_MERCHANT_ADMIN");
        assertThat(details().merchantId()).isEqualTo(EMPLOYER);
        verifyNoInteractions(merchants);
        // Ordinary till traffic must never reach the check (or fill its counter).
        assertNeverChecked();
    }

    @Test
    @DisplayName("a merchantId claim alone is shop scope: it is what MerchantAuthz trusts first")
    void merchantIdClaimAlone_isShopScope() throws Exception {
        // No shop role, no shopId: just the pin, next to another business.
        run(org(token("CUSTOMER").claim("merchantId", EMPLOYER.toString()), OTHER_ORG, "OWNER"));

        assertThat(authorities()).doesNotContain("ROLE_MERCHANT_ADMIN");
        assertThat(details().organizationId()).isNull();
        assertThat(details().merchantId()).isEqualTo(EMPLOYER);
        assertThat(count("withheld")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a merchantId claim alone, next to the organization that owns it, is looked up and kept")
    void merchantIdClaimAlone_ownedByTheOrganization_isKept() throws Exception {
        run(org(token("CUSTOMER").claim("merchantId", EMPLOYER.toString()), EMPLOYER_ORG, "ADMIN"));

        assertThat(authorities()).contains("ROLE_MERCHANT_ADMIN");
        assertThat(details().organizationId()).isEqualTo(EMPLOYER_ORG);
        assertThat(count("org_owns_pin")).isEqualTo(1.0);
    }

    private void assertNeverChecked() {
        assertThat(count("withheld")).isZero();
        assertThat(count("org_owns_pin")).isZero();
        assertThat(count("lookup_failed")).isZero();
    }

    @Test
    @DisplayName("every outcome is registered at zero")
    void countersRegisteredAtZero() {
        assertThat(count("org_owns_pin")).isZero();
        assertThat(count("withheld")).isZero();
        assertThat(count("lookup_failed")).isZero();
    }
}
