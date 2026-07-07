package com.innbucks.loyaltyservice.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    // OWASP A02 stage-1 RS256/JWKS migration (dual-verify). Optional RSA public
    // key: when set, this service verifies BOTH HS256 and RS256 tokens (selected
    // by the token's own `alg` header). When unset it behaves exactly as before
    // (HS256 only). This is a verifier — it never mints, so no private key here.
    @Value("${jwt.public-key:}")
    private String publicKeyPem;

    private PublicKey rsaPublicKey;
    private Locator<Key> keyLocator;
    private volatile boolean keysReady;

    // @PostConstruct validates config at boot under Spring; ensureKeyMaterial()
    // also runs lazily on first use so plain unit tests that `new JwtUtil()` +
    // set fields via reflection (no Spring lifecycle) still work. Idempotent.
    @PostConstruct
    void initKeyMaterial() {
        ensureKeyMaterial();
    }

    private void ensureKeyMaterial() {
        if (keysReady) {
            return;
        }
        synchronized (this) {
            if (keysReady) {
                return;
            }
            SecretKey hmacKey = getSigningKey();
            if (publicKeyPem != null && !publicKeyPem.isBlank()) {
                this.rsaPublicKey = parseRsaPublicKey(publicKeyPem);
            }
            this.keyLocator = (Header header) -> {
                if (header instanceof ProtectedHeader ph) {
                    String alg = ph.getAlgorithm();
                    if (java.util.Objects.requireNonNullElse(alg, "").startsWith("RS")) {
                        if (rsaPublicKey == null) {
                            throw new io.jsonwebtoken.security.SignatureException(
                                    "RS-signed token presented but no jwt.public-key is configured");
                        }
                        return rsaPublicKey;
                    }
                }
                return hmacKey;
            };
            this.keysReady = true;
        }
    }

    private static PublicKey parseRsaPublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(
                    pem.replaceAll("-----BEGIN [^-]+-----", "")
                            .replaceAll("-----END [^-]+-----", "")
                            .replaceAll("\\s", ""));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid jwt.public-key (expected PKCS#8/X.509 PEM)", e);
        }
    }

    /** Fixed JWT issuer (iss) required on every token this service verifies.
     *  Minted by user-service; a token lacking it (or with a different value)
     *  is rejected even when the shared HS256 signature checks out. */
    public static final String TOKEN_ISSUER = "innbucks-ticketing";

    /** Fixed JWT audience (aud) required on every token this service verifies. */
    public static final String TOKEN_AUDIENCE = "innbucks-app";

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Object raw = getClaims(token).get("roles");
        if (raw instanceof Collection<?> c) {
            return c.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractServices(String token) {
        Object raw = getClaims(token).get("services");
        if (raw instanceof Collection<?> c) {
            return c.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    public Integer extractTier(String token) {
        return getClaims(token).get("tier", Integer.class);
    }

    public Boolean extractVerified(String token) {
        return getClaims(token).get("verified", Boolean.class);
    }

    public String extractPhoneNumber(String token) {
        return getClaims(token).get("phoneNumber", String.class);
    }

    public UUID extractMerchantId(String token) {
        return extractUuidClaim(token, "merchantId");
    }

    public UUID extractShopId(String token) {
        return extractUuidClaim(token, "shopId");
    }

    /**
     * Stable cross-service UUID of the caller, read from the {@code userUuid}
     * claim that user-service already mints on every access token. This is the
     * key tenant membership is checked against (see {@code TenantContext}).
     * Returns null for tokens minted before the claim landed — those callers
     * fall back to the email check.
     */
    public UUID extractUserId(String token) {
        return extractUuidClaim(token, "userUuid");
    }

    /**
     * Per-user session epoch from the {@code tokenVersion} claim (OWASP A07 /
     * CWE-613). {@link JwtFilter} compares it against the fleet-current value
     * published to shared Redis ({@code auth:tokenver:<userUuid>}) to reject
     * tokens superseded by a newer login / password change. Returns
     * {@code null} when the claim is absent or unparseable — a legacy token
     * without the claim carries no version to enforce, so the filter fails
     * open rather than 401ing it.
     */
    public Long extractTokenVersion(String token) {
        try {
            Object raw = getClaims(token).get("tokenVersion");
            if (raw instanceof Number n) return n.longValue();
            if (raw == null) return null;
            return Long.parseLong(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private UUID extractUuidClaim(String token, String claim) {
        String raw = getClaims(token).get(claim, String.class);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    /**
     * True when the JWT carries the {@code mustChangePassword} claim. The
     * filter uses this to gate every authenticated request — a user who
     * hasn't rotated their temp password may not call any endpoint in this
     * service. Returns false for absent / unparseable claims.
     */
    public boolean extractMustChangePassword(String token) {
        try {
            Boolean v = getClaims(token).get("mustChangePassword", Boolean.class);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract the {@code homeCountry} claim (ISO 3166-1 alpha-2, e.g.
     * {@code ZW}) — the customer's MSISDN-derived routing key set by
     * user-service's JwtUtil at mint time. Returns null on any failure or
     * when the claim is absent (legacy tokens, staff tokens without an
     * MSISDN, customers whose phone prefix isn't a known InnBucks market).
     * JwtFilter pushes it into MDC for the request's lifetime.
     */
    public String extractHomeCountry(String token) {
        try {
            String home = getClaims(token).get("homeCountry", String.class);
            return (home == null || home.isBlank()) ? null : home;
        } catch (Exception e) {
            return null;
        }
    }

    private Claims getClaims(String token) {
        ensureKeyMaterial();
        return Jwts.parser()
                .keyLocator(keyLocator)
                .requireIssuer(TOKEN_ISSUER)
                .requireAudience(TOKEN_AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
