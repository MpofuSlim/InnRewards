package com.innbucks.loyaltyservice.security;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A07 / CWE-613 enforcement in {@link JwtFilter}: a token whose {@code
 * tokenVersion} claim is strictly below the fleet-current version published to
 * shared Redis ({@code auth:tokenver:<userUuid>}) is rejected exactly like a
 * revoked token (401 {@code TOKEN_REVOKED}, chain halted); a current token, an
 * absent stored version, a missing {@code userUuid} claim, and a missing {@code
 * tokenVersion} claim all pass through (fail-open, no regression).
 */
class JwtFilterTokenVersionTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private TokenVersionStore tokenVersionStore;
    private JwtFilter filter;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        RevokedTokenDenylist denylist = mock(RevokedTokenDenylist.class);
        when(denylist.isRevoked(anyString())).thenReturn(false);
        tokenVersionStore = mock(TokenVersionStore.class);
        filter = new JwtFilter(jwtUtil, denylist, tokenVersionStore);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String mint(UUID userUuid, Long tokenVersion) {
        JwtBuilder builder = Jwts.builder()
                .subject("user@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (userUuid != null) builder.claim("userUuid", userUuid.toString());
        if (tokenVersion != null) builder.claim("tokenVersion", tokenVersion);
        return builder.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    private MockFilterChain invoke(String token, MockHttpServletResponse res) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/loyalty/points");
        req.addHeader("Authorization", "Bearer " + token);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return chain;
    }

    @Test
    void supersededToken_isRejectedLikeRevoked() throws Exception {
        UUID u = UUID.randomUUID();
        when(tokenVersionStore.currentVersion(u.toString())).thenReturn(5L);

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = invoke(mint(u, 1L), res);

        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("TOKEN_REVOKED"));
        assertNull(chain.getRequest(), "filter must halt the chain on a superseded token");
    }

    @Test
    void currentToken_isAllowed() throws Exception {
        UUID u = UUID.randomUUID();
        when(tokenVersionStore.currentVersion(u.toString())).thenReturn(5L);

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = invoke(mint(u, 6L), res); // newer than stored — allowed

        assertNotEquals(401, res.getStatus());
        assertNotNull(chain.getRequest(), "a current token must pass through");
    }

    @Test
    void noStoredVersion_isAllowed() throws Exception {
        UUID u = UUID.randomUUID();
        when(tokenVersionStore.currentVersion(u.toString())).thenReturn(null);

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = invoke(mint(u, 1L), res);

        assertNotNull(chain.getRequest(), "no published version means nothing to enforce");
    }

    @Test
    void missingUserUuidClaim_isAllowed_andStoreNotConsulted() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = invoke(mint(null, 1L), res);

        assertNotNull(chain.getRequest(), "legacy token without userUuid must pass through");
        verify(tokenVersionStore, never()).currentVersion(anyString());
    }

    @Test
    void missingTokenVersionClaim_failsOpen() throws Exception {
        UUID u = UUID.randomUUID();
        when(tokenVersionStore.currentVersion(u.toString())).thenReturn(5L);

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = invoke(mint(u, null), res);

        assertNotNull(chain.getRequest(), "token with no tokenVersion claim must pass (no regression)");
    }
}
