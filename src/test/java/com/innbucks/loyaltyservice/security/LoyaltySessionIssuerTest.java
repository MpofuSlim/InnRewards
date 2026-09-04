package com.innbucks.loyaltyservice.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The loyalty session token this service mints after a customer proves a phone
 * with their own bank session.
 *
 * <p>The shape IS the security boundary, so these cases pin it rather than the
 * behaviour around it:
 * <ul>
 *   <li><b>the roles list is empty</b> — the single property that keeps this
 *       token inert in booking, payment, event and seat, all of which gate on
 *       {@code hasRole('CUSTOMER')}. If it ever carried roles, one phone-proof
 *       would become a fleet-wide passwordless login;</li>
 *   <li><b>no userId claim</b> — an OTP or a bank session proves a phone, not
 *       an identity in our user table, and loyalty's ownership checks compare
 *       the phone;</li>
 *   <li><b>the scope marker and the phone</b> — {@code JwtFilter} grants
 *       nothing without both.</li>
 * </ul>
 */
class LoyaltySessionIssuerTest {

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hmac-sha256-signing-0123456789";
    private static final String PHONE = "+263777224008";

    private LoyaltySessionIssuer issuer;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        issuer = new LoyaltySessionIssuer(jwtUtil);
        ReflectionTestUtils.setField(issuer, "ttlSeconds", 43200L);
    }

    private io.jsonwebtoken.Claims claimsOf(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    @Test
    @DisplayName("SECURITY: the roles claim is EMPTY — this is what keeps the token inert fleet-wide")
    void rolesAreEmpty() {
        var claims = claimsOf(issuer.issue(PHONE));

        assertThat(claims.get("roles", List.class)).isEmpty();
    }

    @Test
    @DisplayName("carries the loyalty-session scope marker and the proved phone")
    void carriesScopeAndPhone() {
        var claims = claimsOf(issuer.issue(PHONE));

        assertThat(claims.get("services", List.class))
                .containsExactly(LoyaltySessionIssuer.LOYALTY_SESSION_SCOPE);
        assertThat(claims.get("phoneNumber", String.class)).isEqualTo(PHONE);
        assertThat(claims.getSubject()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("carries NO userId, tier or verified claim")
    void carriesNoIdentityClaims() {
        // A phone proof is not KYC and not an identity in our user table.
        // It also means the fleet's tokenVersion revocation cannot reach this
        // token — which is why the TTL is the revocation story.
        var claims = claimsOf(issuer.issue(PHONE));

        assertThat(claims.get("userId")).isNull();
        assertThat(claims.get("tier")).isNull();
        assertThat(claims.get("verified")).isNull();
    }

    @Test
    @DisplayName("carries the issuer and audience loyalty's own verifier requires")
    void carriesIssuerAndAudience() {
        // JwtUtil.getClaims requires both; a mint that skipped them would
        // produce a token this very service refuses.
        var claims = claimsOf(issuer.issue(PHONE));

        assertThat(claims.getIssuer()).isEqualTo(JwtUtil.TOKEN_ISSUER);
        assertThat(claims.getAudience()).contains(JwtUtil.TOKEN_AUDIENCE);
    }

    @Test
    @DisplayName("the token this service mints is one this service accepts")
    void roundTripsThroughOurOwnVerifier() {
        // The end-to-end property that matters: mint here, verify with the
        // production extractors JwtFilter uses.
        JwtUtil verifier = new JwtUtil();
        ReflectionTestUtils.setField(verifier, "secret", SECRET);

        String token = issuer.issue(PHONE);

        assertThat(verifier.extractPhoneNumber(token)).isEqualTo(PHONE);
        assertThat(verifier.extractRoles(token)).isEmpty();
        assertThat(verifier.extractServices(token))
                .containsExactly(LoyaltySessionIssuer.LOYALTY_SESSION_SCOPE);
    }

    @Test
    @DisplayName("expiry honours the configured TTL")
    void expiryHonoursTtl() {
        ReflectionTestUtils.setField(issuer, "ttlSeconds", 60L);

        var claims = claimsOf(issuer.issue(PHONE));
        long lifetimeMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();

        assertThat(lifetimeMs).isEqualTo(60_000L);
        assertThat(issuer.ttlSeconds()).isEqualTo(60L);
    }

    @Test
    @DisplayName("a blank phone is refused rather than minting a token that owns nothing")
    void blankPhoneIsRefused() {
        // A session with no phone passes JwtFilter's scope check but matches no
        // account downstream — a caller who is authenticated and owns nothing.
        assertThatThrownBy(() -> issuer.issue(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issue("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the scope is distinct from user-service's OTP marker")
    void scopeIsItsOwnValue() {
        // Both grant the same role, but keeping them distinct means an incident
        // affecting one proof channel can be scoped to the tokens it minted.
        assertThat(LoyaltySessionIssuer.LOYALTY_SESSION_SCOPE).isEqualTo("loyalty-session");
        assertThat(LoyaltySessionIssuer.LOYALTY_SESSION_SCOPE)
                .isNotEqualTo(JwtFilter.LOYALTY_OTP_SCOPE);
    }
}
