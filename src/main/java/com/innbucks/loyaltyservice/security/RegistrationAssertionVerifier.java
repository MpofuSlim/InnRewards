package com.innbucks.loyaltyservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

/**
 * Verifies a registration assertion: a short-lived, phone-scoped token signed by
 * the app's backend saying "the owner of this number authenticated with me".
 *
 * <p>This service holds only the PUBLIC key. That is the point of choosing a
 * signature over a shared key — there is no credential here that, if this
 * service or its config leaked, would let anyone register a phone. The
 * corresponding private key never leaves the asserting party.
 *
 * <p>Every check below exists to stop a specific attack:
 * <ul>
 *   <li><b>Asymmetric algorithms only.</b> The locator returns a
 *       {@link PublicKey} and the algorithm is allow-listed to the RSA and
 *       ECDSA families. jjwt refuses to verify an HMAC token against a
 *       PublicKey, which kills the classic
 *       alg-confusion attack (re-sign with HS256 using the public key bytes as
 *       the secret); the allow-list also rejects {@code alg: none}.</li>
 *   <li><b>Issuer and audience are required.</b> A token minted for some other
 *       relying party — including a fleet JWT from a compromised sibling
 *       service — must not double as a registration proof.</li>
 *   <li><b>Bounded lifetime.</b> {@code exp - iat} may not exceed
 *       {@code max-ttl-seconds}. Without it a caller could mint a
 *       ten-year assertion, and a single capture would be permanent.</li>
 *   <li><b>{@code jti} and {@code iat} are required</b> so the caller can be
 *       held to the monotonic replay guard in
 *       {@code UserService.registerPhone}.</li>
 * </ul>
 *
 * <p>A second, "previous" public key can be configured so a key rotation has an
 * overlap window instead of a cliff: both are tried, newest first.
 */
@Component
@Slf4j
public class RegistrationAssertionVerifier {

    /** Signature algorithms we accept. Asymmetric only — see the class javadoc. */
    private static final Set<String> ALLOWED_ALGS = Set.of("RS256", "RS384", "RS512", "ES256", "ES384", "ES512");

    private final String publicKeyPem;
    private final String previousPublicKeyPem;
    private final String issuer;
    private final String audience;
    private final long maxTtlSeconds;

    private PublicKey currentKey;
    private PublicKey previousKey;

    public RegistrationAssertionVerifier(
            @Value("${loyalty.registration.partner.public-key:}") String publicKeyPem,
            @Value("${loyalty.registration.partner.previous-public-key:}") String previousPublicKeyPem,
            @Value("${loyalty.registration.partner.issuer:innbucks-app}") String issuer,
            @Value("${loyalty.registration.partner.audience:innbucks-loyalty}") String audience,
            @Value("${loyalty.registration.partner.max-ttl-seconds:300}") long maxTtlSeconds) {
        this.publicKeyPem = publicKeyPem;
        this.previousPublicKeyPem = previousPublicKeyPem;
        this.issuer = issuer;
        this.audience = audience;
        this.maxTtlSeconds = maxTtlSeconds;
    }

    /**
     * Parses the configured keys at boot so an unusable key fails fast, rather
     * than at the first real assertion. Also callable directly so plain-{@code new}
     * unit tests work without a Spring lifecycle. Idempotent.
     */
    @PostConstruct
    public void parseKeys() {
        this.currentKey = parseIfPresent(publicKeyPem, "loyalty.registration.partner.public-key");
        this.previousKey = parseIfPresent(previousPublicKeyPem, "loyalty.registration.partner.previous-public-key");
    }

    /** True when at least one usable verification key is configured. */
    public boolean isConfigured() {
        return currentKey != null || previousKey != null;
    }

    /**
     * Verifies the assertion and returns what it proves.
     *
     * @throws AssertionInvalidException on ANY failure. The caller turns every
     *         one into the same opaque 401 — telling an attacker which check
     *         failed is free help.
     */
    public VerifiedRegistration verify(String compactJws) {
        if (compactJws == null || compactJws.isBlank()) {
            throw new AssertionInvalidException("empty assertion");
        }
        if (!isConfigured()) {
            throw new AssertionInvalidException("no verification key configured");
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(allowListedKeyLocator())
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .clockSkewSeconds(30)
                    .build()
                    .parseSignedClaims(compactJws)
                    .getPayload();
        } catch (Exception e) {
            throw new AssertionInvalidException("signature or claims rejected: " + e.getClass().getSimpleName());
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AssertionInvalidException("no subject");
        }
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            throw new AssertionInvalidException("no jti");
        }
        if (claims.getIssuedAt() == null || claims.getExpiration() == null) {
            throw new AssertionInvalidException("iat and exp are both required");
        }
        Instant issuedAt = claims.getIssuedAt().toInstant();
        long ttl = claims.getExpiration().toInstant().getEpochSecond() - issuedAt.getEpochSecond();
        if (ttl > maxTtlSeconds) {
            throw new AssertionInvalidException("lifetime " + ttl + "s exceeds the permitted " + maxTtlSeconds + "s");
        }
        return new VerifiedRegistration(subject, issuedAt, jti);
    }

    /**
     * What a valid assertion proves: this phone, asserted at this instant, under
     * this token id. Nothing else from the token is trusted or carried forward.
     */
    public record VerifiedRegistration(String phoneNumber, Instant assertedAt, String jti) {}

    /** Any reason an assertion was not accepted. Never shown to the caller. */
    public static class AssertionInvalidException extends RuntimeException {
        public AssertionInvalidException(String message) {
            super(message);
        }
    }

    /**
     * Returns the verification key only for an allow-listed asymmetric
     * algorithm. Same lambda shape as {@code JwtUtil}'s locator.
     *
     * <p>Refusing here, rather than letting jjwt select a key and fail later,
     * means a symmetric or unsigned token never reaches a comparison against key
     * material at all.
     */
    private Locator<Key> allowListedKeyLocator() {
        return (Header header) -> {
            if (!(header instanceof ProtectedHeader ph)) {
                throw new SignatureException("unsigned assertion");
            }
            String alg = ph.getAlgorithm();
            if (alg == null || !ALLOWED_ALGS.contains(alg)) {
                throw new SignatureException("unsupported assertion algorithm: " + alg);
            }
            return currentKey != null ? currentKey : previousKey;
        };
    }

    private static PublicKey parseIfPresent(String pem, String propertyName) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            byte[] der = Base64.getDecoder().decode(
                    pem.replaceAll("-----BEGIN [^-]+-----", "")
                            .replaceAll("-----END [^-]+-----", "")
                            .replaceAll("\\s", ""));
            // Mirrors JwtUtil.parseRsaPublicKey. RSA covers the RS* algorithms;
            // an EC key would need a KeyFactory for "EC" — add it the day an
            // asserting party actually uses ES*, rather than guessing now.
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            // Fail the boot. A key that cannot be parsed would otherwise present
            // as "every assertion is invalid", which reads like the partner's
            // fault and is not.
            throw new IllegalStateException("Invalid " + propertyName + " (expected an X.509 / SubjectPublicKeyInfo PEM)", e);
        }
    }
}
