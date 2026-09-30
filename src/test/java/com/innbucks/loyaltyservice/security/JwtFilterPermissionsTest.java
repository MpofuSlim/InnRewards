package com.innbucks.loyaltyservice.security;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code perms} claim becomes bare authorities — and ONLY permission-shaped
 * ones. The support endpoints are gated on those authorities, so the whole
 * safety argument is here: a permission can only ever ADD support authority, it
 * can never be dressed up as a role, a service scope, a tier or a verification
 * flag, and so no existing check can be satisfied through this claim.
 */
class JwtFilterPermissionsTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private static final String PHONE = "+263771234567";

    private JwtFilter filter;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        RevokedTokenDenylist denylist = mock(RevokedTokenDenylist.class);
        when(denylist.isRevoked(anyString())).thenReturn(false);
        filter = new JwtFilter(jwtUtil, denylist, mock(TokenVersionStore.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static JwtBuilder base(List<String> roles, List<String> services) {
        return Jwts.builder()
                .subject("agent@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .claim("roles", roles)
                .claim("services", services);
    }

    private static String sign(JwtBuilder builder) {
        return builder.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    private Set<String> authoritiesFor(String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/loyalty/support/activity");
        req.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Set.of();
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("each well-formed code becomes a bare authority, beside the role")
    void wellFormedCodes_becomeBareAuthorities() throws Exception {
        Set<String> authorities = authoritiesFor(sign(base(List.of("SUPPORT_AGENT"), List.of())
                .claim("perms", List.of("loyalty-support:read", "loyalty-support:manage",
                        "customer-messages:send", "marketplace-support:read"))));

        assertThat(authorities).contains("ROLE_SUPPORT_AGENT", "loyalty-support:read",
                "loyalty-support:manage", "customer-messages:send", "marketplace-support:read");
        // Bare: never prefixed, so a permission and a role cannot collide.
        assertThat(authorities).noneMatch(a -> a.startsWith("ROLE_loyalty") || a.startsWith("PERM_"));
    }

    @Test
    @DisplayName("an entry spelled like a role, a scope, a tier, VERIFIED or a wildcard is DROPPED")
    void entriesThatAreNotPermissionCodes_areDropped() throws Exception {
        Set<String> authorities = authoritiesFor(sign(base(List.of("CUSTOMER"), List.of())
                .claim("perms", List.of(
                        "ROLE_SUPER_ADMIN",          // would satisfy hasRole('SUPER_ADMIN')
                        "SERVICE_LOYALTY-OTP",       // the /loyalty/session/exchange scope marker
                        "SERVICE_LOYALTY-SESSION",
                        "VERIFIED",
                        "TIER_4",
                        "*",                         // user-service expands the wildcard; a raw one grants nothing
                        "loyalty-support",           // no namespace
                        "Loyalty-Support:read",      // not lowercase
                        "loyalty-support:",          // empty segment
                        ":read",
                        "loyalty-support:read ",     // trailing space
                        "loyalty-support:read"))));

        assertThat(authorities)
                .doesNotContain("ROLE_SUPER_ADMIN", "SERVICE_LOYALTY-OTP", "SERVICE_LOYALTY-SESSION",
                        "TIER_4", "*", "loyalty-support", "Loyalty-Support:read", "loyalty-support:",
                        ":read", "loyalty-support:read ")
                .contains("loyalty-support:read", "ROLE_CUSTOMER");
        // VERIFIED is still granted by the claim that owns it — not by perms.
        assertThat(authorities).doesNotContain("VERIFIED");
    }

    @Test
    @DisplayName("no perms claim, a non-array claim or non-string entries: no permission at all (fail closed)")
    void missingOrMalformedClaim_grantsNothing() throws Exception {
        Set<String> none = authoritiesFor(sign(base(List.of("SUPER_ADMIN"), List.of())));
        Set<String> scalar = authoritiesFor(sign(base(List.of("SUPER_ADMIN"), List.of())
                .claim("perms", "loyalty-support:read")));
        Set<String> objects = authoritiesFor(sign(base(List.of("SUPER_ADMIN"), List.of())
                .claim("perms", List.of(Map.of("code", "loyalty-support:read"), 42))));

        for (Set<String> authorities : List.of(none, scalar, objects)) {
            assertThat(authorities).containsExactly("ROLE_SUPER_ADMIN");
        }
    }

    @Test
    @DisplayName("a SUPER_ADMIN role alone holds no support permission — there is no roles-to-perms backfill here")
    void superAdminRole_withoutPerms_holdsNoPermission() throws Exception {
        Set<String> authorities = authoritiesFor(sign(base(List.of("SUPER_ADMIN"), List.of())));

        assertThat(authorities).noneMatch(a -> a.contains(":"));
    }

    @Test
    @DisplayName("a customer's authorities are unchanged by permissions: the ROLE_ set is still exactly {CUSTOMER}")
    void customerToken_roleSetUnchanged() throws Exception {
        Set<String> without = authoritiesFor(sign(base(List.of("CUSTOMER"), List.of()).claim("tier", 2)));
        SecurityContextHolder.clearContext();
        Set<String> with = authoritiesFor(sign(base(List.of("CUSTOMER"), List.of()).claim("tier", 2)
                .claim("perms", List.of("loyalty-support:read", "ROLE_SUPER_ADMIN"))));

        // Everything a role, scope or tier check can see is identical...
        assertThat(roleLike(with)).isEqualTo(roleLike(without));
        // ...and TenantContext's plain-customer test (ROLE_ authorities == {ROLE_CUSTOMER}) still holds.
        assertThat(with.stream().filter(a -> a.startsWith("ROLE_")).collect(Collectors.toSet()))
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("a phone-scoped loyalty session never carries a permission, whatever its claim says")
    void phoneScopedSession_ignoresPerms() throws Exception {
        Set<String> authorities = authoritiesFor(sign(base(List.of(), List.of("loyalty-otp"))
                .claim("phoneNumber", PHONE)
                .claim("perms", List.of("loyalty-support:read", "customer-messages:send"))));

        assertThat(authorities).contains("ROLE_CUSTOMER").noneMatch(a -> a.contains(":"));
    }

    @Test
    @DisplayName("the static helper drops what the shape refuses and keeps order without duplicates")
    void helper_dedupesAndFilters() {
        assertThat(JwtFilter.permissionAuthorities(List.of("b-x:read", "ROLE_X", "a-y:write", "b-x:read")))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("b-x:read", "a-y:write");
        assertThat(JwtFilter.permissionAuthorities(null)).isEmpty();
    }

    private static Set<String> roleLike(Set<String> authorities) {
        return authorities.stream().filter(a -> !a.contains(":")).collect(Collectors.toSet());
    }
}
