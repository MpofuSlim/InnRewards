package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.entity.VoucherPartner;
import com.innbucks.loyaltyservice.entity.VoucherPartnerKey;
import com.innbucks.loyaltyservice.repository.VoucherPartnerKeyRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnerRepository;
import com.innbucks.loyaltyservice.testsupport.PartnerJws;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every check of a partner request (V62), each failing alone: the reason that
 * reaches the log and the metric, while the caller always gets the same 401.
 */
class PartnerRequestVerifierTest {

    private static final String AUD = "innbucks-loyalty-vouchers:zw-staging";
    private static final String ISS = "innbucks-middleware";
    private static final String KID = "mw-stg-2026-10";

    private final VoucherPartnerKeyRepository keys = mock(VoucherPartnerKeyRepository.class);
    private final VoucherPartnerRepository partners = mock(VoucherPartnerRepository.class);
    private final Set<String> seenJtis = new HashSet<>();
    private boolean jtiStoreDown;
    private final PartnerJtiStore jtiStore = (key, ttl) -> {
        if (jtiStoreDown) {
            throw new PartnerAuthException.ReplayCheckUnavailable(null);
        }
        return seenJtis.add(key);
    };

    private KeyPair ec;
    private VoucherPartner partner;
    private VoucherPartnerKey key;
    private PartnerRequestVerifier verifier;
    private InetAddress ip;

    @BeforeEach
    void setUp() throws Exception {
        ec = PartnerJws.ecKeyPair();
        partner = new VoucherPartner();
        partner.setId(UUID.randomUUID());
        partner.setName("InnBucks middleware");
        partner.setIssuer(ISS);
        partner.setAllowedIps("203.0.113.10, 198.51.100.0/28");
        key = new VoucherPartnerKey();
        key.setPartnerId(partner.getId());
        key.setKid(KID);
        key.setAlg(VoucherPartnerKey.Alg.ES256);
        key.setPublicKeyPem(PartnerJws.pem(ec.getPublic()));
        when(keys.findByKid(anyString())).thenReturn(Optional.empty());
        when(keys.findByKid(KID)).thenReturn(Optional.of(key));
        when(partners.findById(partner.getId())).thenReturn(Optional.of(partner));
        verifier = verifier(Clock.systemUTC());
        ip = InetAddress.getByName("203.0.113.10");
    }

    private PartnerRequestVerifier verifier(Clock clock) {
        VoucherPartnerProperties props = new VoucherPartnerProperties(true, AUD, null, null, null, null, null, null,
                null, null, null, null, null);
        return new PartnerRequestVerifier(keys, partners, props, jtiStore, clock);
    }

    private Map<String, Object> body() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("merchant", "MWOS");
        b.put("amount", new BigDecimal("20.00"));
        return b;
    }

    /** Signed with the EC key whatever the header claims, so a lying {@code alg} is what is tested. */
    private String signed(Map<String, Object> header, Map<String, Object> claims) {
        return PartnerJws.sign(toJson(header).getBytes(StandardCharsets.UTF_8),
                toJson(claims).getBytes(StandardCharsets.UTF_8), ec.getPrivate(), "ES256");
    }

    private String valid(String op) {
        return signed(PartnerJws.header("ES256", KID), PartnerJws.claims(ISS, AUD, op, body()));
    }

    private String reason(String jws, String op, InetAddress from) {
        try {
            verifier.verify(jws, op, from);
            return "accepted";
        } catch (PartnerAuthException e) {
            return e.reason();
        } catch (PartnerAuthException.ReplayCheckUnavailable e) {
            return "unavailable";
        }
    }

    @Test
    void aValidRequestIsAccepted_andItsAmountsAreExactDecimals() throws Exception {
        PartnerRequest r = verifier.verify(valid("generate"), "generate", ip);

        assertThat(r.partner()).isSameAs(partner);
        assertThat(r.kid()).isEqualTo(KID);
        assertThat(r.op()).isEqualTo("generate");
        assertThat(r.body().get("amount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(r.body().get("amount").isBigDecimal()).isTrue();
    }

    @Test
    void anRs256KeyWorksToo() throws Exception {
        KeyPair rsa = PartnerJws.rsaKeyPair(2048);
        key.setAlg(VoucherPartnerKey.Alg.RS256);
        key.setPublicKeyPem(PartnerJws.pem(rsa.getPublic()));
        verifier = verifier(Clock.systemUTC());
        String jws = PartnerJws.sign(PartnerJws.header("RS256", KID), PartnerJws.claims(ISS, AUD, "redeem", body()),
                rsa.getPrivate());

        assertThat(verifier.verify(jws, "redeem", ip).op()).isEqualTo("redeem");
    }

    @Test
    void aKeyOnOneLineWithEscapedNewlinesIsAccepted() throws Exception {
        key.setPublicKeyPem(PartnerJws.pem(ec.getPublic()).replace("\n", "\\n"));
        verifier = verifier(Clock.systemUTC());

        assertThat(reason(valid("generate"), "generate", ip)).isEqualTo("accepted");
    }

    @Test
    void anyAlgorithmButTheRegisteredOneIsRefused() {
        for (String alg : List.of("none", "HS256", "RS256", "ES384")) {
            Map<String, Object> h = PartnerJws.header(alg, KID);
            assertThat(reason(signed(h, PartnerJws.claims(ISS, AUD, "generate", body())), "generate", ip))
                    .as(alg).isEqualTo("alg");
        }
    }

    @Test
    void aHeaderThatPointsAtItsOwnKeyIsRefused() {
        for (String header : List.of("jku", "x5u", "jwk", "x5c", "crit")) {
            Map<String, Object> h = PartnerJws.header("ES256", KID);
            h.put(header, "https://evil.example/key");
            assertThat(reason(signed(h, PartnerJws.claims(ISS, AUD, "generate", body())), "generate", ip))
                    .as(header).isEqualTo("header");
        }
    }

    @Test
    void theWrongTypIsRefused() {
        Map<String, Object> h = PartnerJws.header("ES256", KID);
        h.put("typ", "JWT");
        assertThat(reason(signed(h, PartnerJws.claims(ISS, AUD, "generate", body())), "generate", ip)).isEqualTo("typ");
    }

    @Test
    void anUnknownOrRevokedKeyOrSuspendedPartnerIsRefused() {
        assertThat(reason(signed(PartnerJws.header("ES256", "someone-else"),
                PartnerJws.claims(ISS, AUD, "generate", body())), "generate", ip)).isEqualTo("unknown_kid");

        key.setStatus(VoucherPartnerKey.Status.REVOKED);
        assertThat(reason(valid("generate"), "generate", ip)).isEqualTo("revoked_key");

        key.setStatus(VoucherPartnerKey.Status.ACTIVE);
        partner.setStatus(VoucherPartner.Status.SUSPENDED);
        assertThat(reason(valid("generate"), "generate", ip)).isEqualTo("partner_inactive");
    }

    @Test
    void onlyTheAllowListedAddressesMayCall() throws Exception {
        assertThat(reason(valid("generate"), "generate", InetAddress.getByName("198.51.100.7"))).isEqualTo("accepted");
        assertThat(reason(valid("generate"), "generate", InetAddress.getByName("198.51.100.20"))).isEqualTo("ip");
        assertThat(reason(valid("generate"), "generate", null)).isEqualTo("ip");

        partner.setAllowedIps("");
        assertThat(reason(valid("generate"), "generate", ip)).isEqualTo("ip");
    }

    @Test
    void aChangedPayloadOrAnotherKeysSignatureIsRefused() {
        String jws = valid("generate");
        String[] parts = jws.split("\\.");
        Map<String, Object> claims = PartnerJws.claims(ISS, AUD, "generate", Map.of("merchant", "MWOS",
                "amount", new BigDecimal("2000.00")));
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toJson(claims).getBytes(StandardCharsets.UTF_8));
        assertThat(reason(parts[0] + "." + forgedPayload + "." + parts[2], "generate", ip)).isEqualTo("bad_signature");

        KeyPair other = PartnerJws.ecKeyPair();
        String byOther = PartnerJws.sign(PartnerJws.header("ES256", KID), PartnerJws.claims(ISS, AUD, "generate", body()),
                other.getPrivate());
        assertThat(reason(byOther, "generate", ip)).isEqualTo("bad_signature");
    }

    @Test
    void theClaimsMustNameThisPartnerThisCellAndThisOperation() {
        assertThat(reason(signed(PartnerJws.header("ES256", KID), PartnerJws.claims("someone", AUD, "generate", body())),
                "generate", ip)).isEqualTo("iss");
        assertThat(reason(signed(PartnerJws.header("ES256", KID),
                PartnerJws.claims(ISS, "innbucks-loyalty-vouchers:zw-prod", "generate", body())), "generate", ip))
                .isEqualTo("aud");

        Map<String, Object> twoAudiences = PartnerJws.claims(ISS, AUD, "generate", body());
        twoAudiences.put("aud", List.of(AUD, "innbucks-loyalty-vouchers:zw-prod"));
        assertThat(reason(signed(PartnerJws.header("ES256", KID), twoAudiences), "generate", ip)).isEqualTo("aud");

        Map<String, Object> oneInAnArray = PartnerJws.claims(ISS, AUD, "generate", body());
        oneInAnArray.put("aud", List.of(AUD));
        assertThat(reason(signed(PartnerJws.header("ES256", KID), oneInAnArray), "generate", ip)).isEqualTo("accepted");

        // A request signed for inquire can never be used to redeem.
        assertThat(reason(valid("inquire"), "redeem", ip)).isEqualTo("op");
    }

    @Test
    void theTimesMustHold() {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> future = PartnerJws.claims(ISS, AUD, "generate", body());
        future.put("iat", now + 120);
        future.put("exp", now + 180);
        assertThat(reason(signed(PartnerJws.header("ES256", KID), future), "generate", ip)).isEqualTo("timing");

        Map<String, Object> expired = PartnerJws.claims(ISS, AUD, "generate", body());
        expired.put("iat", now - 200);
        expired.put("exp", now - 100);
        assertThat(reason(signed(PartnerJws.header("ES256", KID), expired), "generate", ip)).isEqualTo("timing");

        Map<String, Object> tooLong = PartnerJws.claims(ISS, AUD, "generate", body());
        tooLong.put("exp", now + 600);
        assertThat(reason(signed(PartnerJws.header("ES256", KID), tooLong), "generate", ip)).isEqualTo("timing");

        Map<String, Object> noJti = PartnerJws.claims(ISS, AUD, "generate", body());
        noJti.remove("jti");
        assertThat(reason(signed(PartnerJws.header("ES256", KID), noJti), "generate", ip)).isEqualTo("jti");
    }

    @Test
    void aReplayedRequestIsRefused_andAnUnanswerableStoreFailsClosed() {
        String jws = valid("redeem");
        assertThat(reason(jws, "redeem", ip)).isEqualTo("accepted");
        assertThat(reason(jws, "redeem", ip)).isEqualTo("replay");

        jtiStoreDown = true;
        assertThat(reason(valid("redeem"), "redeem", ip)).isEqualTo("unavailable");
    }

    @Test
    void aPayloadWithTheSameKeyTwiceIsRefused() {
        long now = Instant.now().getEpochSecond();
        String payload = "{\"iss\":\"" + ISS + "\",\"aud\":\"" + AUD + "\",\"op\":\"generate\",\"jti\":\""
                + UUID.randomUUID() + "\",\"iat\":" + now + ",\"exp\":" + (now + 60)
                + ",\"body\":{\"amount\":1.00,\"amount\":900.00}}";
        String jws = PartnerJws.sign(toJson(PartnerJws.header("ES256", KID)).getBytes(StandardCharsets.UTF_8),
                payload.getBytes(StandardCharsets.UTF_8), ec.getPrivate(), "ES256");
        assertThat(reason(jws, "generate", ip)).isEqualTo("payload");
    }

    @Test
    void garbageIsMalformed() {
        assertThat(reason("not-a-jws", "generate", ip)).isEqualTo("malformed");
        assertThat(reason("", "generate", ip)).isEqualTo("malformed");
        assertThat(reason(null, "generate", ip)).isEqualTo("malformed");
    }

    @Test
    void theClockSkewIsTolerated() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = PartnerJws.claims(ISS, AUD, "generate", body());
        claims.put("iat", now + 20);
        claims.put("exp", now + 80);
        assertThat(reason(signed(PartnerJws.header("ES256", KID), claims), "generate", ip)).isEqualTo("accepted");

        // And the verifier's own clock is the one that counts.
        verifier = verifier(Clock.fixed(Instant.now().plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
        assertThat(reason(valid("generate"), "generate", ip)).isEqualTo("timing");
    }

    @Test
    void aSignatureMadeForAnotherAlgorithmLengthIsRefusedNotThrown() {
        assertThatThrownBy(() -> verifier.verify(valid("generate").replaceAll("\\.[^.]+$", ".AAAA"), "generate", ip))
                .isInstanceOf(PartnerAuthException.class)
                .hasMessage("bad_signature");
    }

    private static String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
