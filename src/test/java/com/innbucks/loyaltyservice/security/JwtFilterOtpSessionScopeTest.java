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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The OTP-proved, phone-scoped customer session — user-service's
 * {@code LoyaltySessionTokenIssuer} mints it, and this filter is the ONLY place
 * in the fleet that turns it into a customer.
 *
 * <p>The safety property under test is an asymmetry: the token carries no
 * {@code roles} claim, so every other service (all of which gate customer
 * endpoints on {@code hasRole('CUSTOMER')}) rejects it without any change of
 * their own, and loyalty grants the role only for this exact shape. Most cases
 * below are therefore about tokens that must NOT get the role — the grant is
 * only as safe as its conditions are narrow.
 */
class JwtFilterOtpSessionScopeTest {

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
        TokenVersionStore tokenVersionStore = mock(TokenVersionStore.class);
        filter = new JwtFilter(jwtUtil, denylist, tokenVersionStore);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Mirrors exactly what user-service's LoyaltySessionTokenIssuer emits. */
    private static String mint(List<String> roles, List<String> services, String phoneNumber) {
        JwtBuilder builder = Jwts.builder()
                .subject(phoneNumber == null ? "someone@example.com" : phoneNumber)
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .claim("roles", roles)
                .claim("services", services);
        if (phoneNumber != null) builder.claim("phoneNumber", phoneNumber);
        return builder.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    private Set<String> authoritiesFor(String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/loyalty/transfer");
        req.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return Set.of();
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("the OTP session token becomes a CUSTOMER inside loyalty")
    void otpSessionToken_grantsCustomer() throws Exception {
        Set<String> authorities = authoritiesFor(mint(List.of(), List.of("loyalty-otp"), PHONE));

        assertThat(authorities).contains("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("the caller's phone reaches CallerDetails — the ownership checks have something to match")
    void otpSessionToken_carriesThePhoneIntoCallerDetails() throws Exception {
        // Without this, requireCallerOwns would compare against null and refuse
        // the customer their own account — the grant would be inert.
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/loyalty/transfer");
        req.addHeader("Authorization", "Bearer " + mint(List.of(), List.of("loyalty-otp"), PHONE));
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(CallerDetails.currentPhoneNumber()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("no phone claim → no role, even with the right scope")
    void scopeWithoutPhone_grantsNothing() throws Exception {
        // A customer who owns nothing and matches nothing is worse than no
        // customer at all: it would pass @PreAuthorize and then fail every
        // ownership check with a confusing 403.
        Set<String> authorities = authoritiesFor(mint(List.of(), List.of("loyalty-otp"), null));

        assertThat(authorities).doesNotContain("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("the plain 'loyalty' service marker does NOT grant the role — only the purpose-minted scope does")
    void plainLoyaltyService_grantsNothing() throws Exception {
        // Staff and full customer tokens can legitimately list "loyalty" among
        // their services. Only a token minted BY the OTP flow may become a
        // customer without a roles claim.
        Set<String> authorities = authoritiesFor(mint(List.of(), List.of("loyalty"), PHONE));

        assertThat(authorities).doesNotContain("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("a token with no roles and no scope stays role-less")
    void noRolesNoScope_grantsNothing() throws Exception {
        Set<String> authorities = authoritiesFor(mint(List.of(), List.of(), PHONE));

        assertThat(authorities).doesNotContain("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("the grant can never ELEVATE a token that already carries roles")
    void tokenWithRoles_isUntouched() throws Exception {
        // The dangerous shape: a low-privilege staff token that also claims the
        // scope. It must keep exactly the roles it was minted with — the branch
        // is gated on the roles claim being EMPTY precisely so this can only
        // ever grant, never widen.
        Set<String> authorities = authoritiesFor(
                mint(List.of("SHOP_USER"), List.of("loyalty-otp"), PHONE));

        assertThat(authorities).contains("ROLE_SHOP_USER");
        assertThat(authorities).doesNotContain("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("the scope marker matches the issuer's constant exactly")
    void scopeConstant_matchesTheIssuer() {
        // user-service's LoyaltySessionTokenIssuer.LOYALTY_OTP_SCOPE lives in
        // ANOTHER REPOSITORY, so nothing but this literal couples the two. A
        // drift would present as every app customer silently losing loyalty
        // access, with no error anywhere to point at it.
        assertThat(JwtFilter.LOYALTY_OTP_SCOPE).isEqualTo("loyalty-otp");
    }
}
