package com.innbucks.loyaltyservice.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the verification contract for a partner registration assertion.
 *
 * <p>Pure JUnit — no Spring context — so each case is a millisecond and is about
 * the token, not the wiring. Every rejection case here is a way an attacker
 * could otherwise get a phone registered without the partner's private key.
 */
class RegistrationAssertionVerifierTest {

    private static final String ISSUER = "innbucks-app";
    private static final String AUDIENCE = "innbucks-loyalty";
    private static final String PHONE = "+263771234567";

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        otherKeyPair = gen.generateKeyPair();
    }

    private static String pem(java.security.PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    private static RegistrationAssertionVerifier verifier() {
        RegistrationAssertionVerifier v = new RegistrationAssertionVerifier(
                pem(keyPair.getPublic()), "", ISSUER, AUDIENCE, 300);
        v.parseKeys();
        return v;
    }

    /** A well-formed assertion, adjustable per test. */
    private static String assertion(java.security.PrivateKey signWith, String subject, String issuer,
                                    String audience, Instant issuedAt, long ttlSeconds, String jti) {
        var builder = Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(ttlSeconds)));
        if (subject != null) builder.subject(subject);
        if (jti != null) builder.id(jti);
        return builder.signWith(signWith, Jwts.SIG.RS256).compact();
    }

    private static String validAssertion() {
        return assertion(keyPair.getPrivate(), PHONE, ISSUER, AUDIENCE, Instant.now(), 120, "jti-1");
    }

    @Test
    @DisplayName("a well-formed assertion yields the phone, its issue time and its jti")
    void validAssertion_isAccepted() {
        var verified = verifier().verify(validAssertion());

        assertThat(verified.phoneNumber()).isEqualTo(PHONE);
        assertThat(verified.jti()).isEqualTo("jti-1");
        assertThat(verified.assertedAt()).isNotNull();
    }

    @Test
    @DisplayName("a token signed by a DIFFERENT key is refused")
    void wrongSigningKey_isRefused() {
        String forged = assertion(otherKeyPair.getPrivate(), PHONE, ISSUER, AUDIENCE, Instant.now(), 120, "jti-1");

        assertThatThrownBy(() -> verifier().verify(forged))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("HS256 signed with the PUBLIC key bytes is refused (alg confusion)")
    void algConfusion_isRefused() {
        // The classic attack on a verifier that picks its algorithm from the
        // token: take the public key everyone can see, use its bytes as an HMAC
        // secret, and the verifier "verifies" your forgery. The allow-list plus
        // jjwt's refusal to HMAC against a PublicKey both stop this.
        byte[] publicBytes = keyPair.getPublic().getEncoded();
        String forged = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(PHONE)
                .id("jti-1")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(new SecretKeySpec(publicBytes, "HmacSHA256"), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> verifier().verify(forged))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("an unsigned token (alg: none) is refused")
    void unsignedToken_isRefused() {
        String unsigned = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(PHONE)
                .id("jti-1")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .compact();

        assertThatThrownBy(() -> verifier().verify(unsigned))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("an expired assertion is refused")
    void expired_isRefused() {
        String stale = assertion(keyPair.getPrivate(), PHONE, ISSUER, AUDIENCE,
                Instant.now().minusSeconds(600), 60, "jti-1");

        assertThatThrownBy(() -> verifier().verify(stale))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("an assertion whose own lifetime exceeds the ceiling is refused")
    void overlongLifetime_isRefused() {
        // Signature and expiry both fine — the objection is that the partner
        // minted a token good for a year. A single capture would be permanent.
        String longLived = assertion(keyPair.getPrivate(), PHONE, ISSUER, AUDIENCE,
                Instant.now(), 86_400, "jti-1");

        assertThatThrownBy(() -> verifier().verify(longLived))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class)
                .hasMessageContaining("exceeds the permitted");
    }

    @Test
    @DisplayName("a token for another issuer or audience is refused")
    void wrongIssuerOrAudience_isRefused() {
        // A fleet JWT from a compromised sibling service carries our issuer and
        // audience, not the partner's, and must not double as a proof of phone
        // ownership.
        String wrongIssuer = assertion(keyPair.getPrivate(), PHONE, "innbucks-ticketing", AUDIENCE,
                Instant.now(), 120, "jti-1");
        String wrongAudience = assertion(keyPair.getPrivate(), PHONE, ISSUER, "innbucks-app",
                Instant.now(), 120, "jti-1");

        assertThatThrownBy(() -> verifier().verify(wrongIssuer))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
        assertThatThrownBy(() -> verifier().verify(wrongAudience))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("missing sub or jti is refused")
    void missingClaims_areRefused() {
        String noSubject = assertion(keyPair.getPrivate(), null, ISSUER, AUDIENCE, Instant.now(), 120, "jti-1");
        String noJti = assertion(keyPair.getPrivate(), PHONE, ISSUER, AUDIENCE, Instant.now(), 120, null);

        assertThatThrownBy(() -> verifier().verify(noSubject))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
        assertThatThrownBy(() -> verifier().verify(noJti))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class)
                .hasMessageContaining("jti");
    }

    @Test
    @DisplayName("blank and null assertions are refused, not NPE'd")
    void blankAssertion_isRefused() {
        assertThatThrownBy(() -> verifier().verify(null))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
        assertThatThrownBy(() -> verifier().verify("   "))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("with no key configured the verifier reports unconfigured and refuses everything")
    void unconfigured_refusesEverything() {
        // Fail-closed: a cell that switched the feature on but never provisioned
        // the key must refuse, not accept. The controller turns isConfigured()
        // into a 503 so the operator sees a provisioning fault rather than a
        // stream of 401s that look like the partner's bug.
        RegistrationAssertionVerifier v = new RegistrationAssertionVerifier("", "", ISSUER, AUDIENCE, 300);
        v.parseKeys();

        assertThat(v.isConfigured()).isFalse();
        assertThatThrownBy(() -> v.verify(validAssertion()))
                .isInstanceOf(RegistrationAssertionVerifier.AssertionInvalidException.class);
    }

    @Test
    @DisplayName("the previous key keeps verifying during a rotation overlap")
    void previousKey_isAcceptedDuringRotation() {
        // Rotation has to be an overlap, not a cliff: the partner cannot swap
        // its signing key at the same instant we swap the verification key.
        RegistrationAssertionVerifier v = new RegistrationAssertionVerifier(
                "", pem(keyPair.getPublic()), ISSUER, AUDIENCE, 300);
        v.parseKeys();

        assertThat(v.isConfigured()).isTrue();
        assertThat(v.verify(validAssertion()).phoneNumber()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("an unparseable key fails at construction, not at the first assertion")
    void malformedKey_failsFast() {
        RegistrationAssertionVerifier v = new RegistrationAssertionVerifier(
                "-----BEGIN PUBLIC KEY-----\nnot-base64!!\n-----END PUBLIC KEY-----",
                "", ISSUER, AUDIENCE, 300);

        assertThatThrownBy(v::parseKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loyalty.registration.partner.public-key");
    }
}
