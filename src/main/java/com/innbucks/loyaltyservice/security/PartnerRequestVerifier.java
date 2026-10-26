package com.innbucks.loyaltyservice.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.entity.VoucherPartner;
import com.innbucks.loyaltyservice.entity.VoucherPartnerKey;
import com.innbucks.loyaltyservice.repository.VoucherPartnerKeyRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Checks a partner API request (V62): one compact JWS, signed by a key the
 * partner registered with us.
 *
 * <p>The checks, in order. Every failure is a {@link PartnerAuthException} whose
 * reason is logged and counted, and every one answers the caller the same
 * opaque 401:
 * <ol>
 *   <li>shape: three base64url parts;</li>
 *   <li>header: {@code typ} is {@code voucher-request+jwt}; none of {@code jku},
 *       {@code x5u}, {@code jwk}, {@code x5c}, {@code crit}, which would let the
 *       request say where to find its own key;</li>
 *   <li>key: {@code kid} names an ACTIVE key of an ACTIVE partner, and
 *       {@code alg} is exactly the algorithm registered for it, so {@code none},
 *       HS256 and algorithm confusion are all refused;</li>
 *   <li>source: the client address (nginx's X-Real-IP) is on the partner's
 *       allow-list;</li>
 *   <li>signature;</li>
 *   <li>claims: {@code iss} is the partner's, {@code aud} is exactly this
 *       cell's one audience, {@code op} matches the path, and the times hold:
 *       {@code iat} not in the future, {@code exp} not past, {@code exp - iat}
 *       at most the configured lifetime (all with the clock skew allowed);</li>
 *   <li>replay: the {@code jti} is recorded in Redis for the request's
 *       remaining lifetime, and a second use is refused. Last, so that unsigned
 *       junk can never fill Redis. If Redis cannot answer, the request is
 *       refused with a 503, never let through.</li>
 * </ol>
 *
 * <p>The payload is parsed with numbers as exact decimals and duplicate keys
 * refused, so {@code 20.00} is never a double and a payload cannot carry two
 * different amounts.
 */
@Component
public class PartnerRequestVerifier {

    public static final String TYP = "voucher-request+jwt";
    static final Set<String> FORBIDDEN_HEADERS = Set.of("jku", "x5u", "jwk", "x5c", "crit");
    private static final Pattern COMPACT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern JTI = Pattern.compile("[A-Za-z0-9_-]{8,128}");
    private static final Duration REPLAY_GRACE = Duration.ofSeconds(60);

    /** Strict reader: exact decimals, no duplicate keys, nothing after the value. */
    static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final VoucherPartnerKeyRepository keys;
    private final VoucherPartnerRepository partners;
    private final VoucherPartnerProperties props;
    private final PartnerJtiStore jtiStore;
    private final Clock clock;
    /** Parsed public keys by kid + PEM, so a key is parsed once, not per request. */
    private final Map<String, PublicKey> parsedKeys = new ConcurrentHashMap<>();

    @Autowired
    public PartnerRequestVerifier(VoucherPartnerKeyRepository keys, VoucherPartnerRepository partners,
                                  VoucherPartnerProperties props, PartnerJtiStore jtiStore) {
        this(keys, partners, props, jtiStore, Clock.systemUTC());
    }

    PartnerRequestVerifier(VoucherPartnerKeyRepository keys, VoucherPartnerRepository partners,
                           VoucherPartnerProperties props, PartnerJtiStore jtiStore, Clock clock) {
        this.keys = keys;
        this.partners = partners;
        this.props = props;
        this.jtiStore = jtiStore;
        this.clock = clock;
    }

    public PartnerRequest verify(String compact, String expectedOp, InetAddress clientIp)
            throws PartnerAuthException, PartnerAuthException.ReplayCheckUnavailable {
        String jws = compact == null ? "" : compact.strip();
        if (!COMPACT.matcher(jws).matches()) {
            throw new PartnerAuthException("malformed");
        }
        int first = jws.indexOf('.');
        int second = jws.indexOf('.', first + 1);
        String headerPart = jws.substring(0, first);
        String payloadPart = jws.substring(first + 1, second);
        String signaturePart = jws.substring(second + 1);

        JsonNode header = readObject(headerPart, "header");
        for (String forbidden : FORBIDDEN_HEADERS) {
            if (header.has(forbidden)) {
                throw new PartnerAuthException("header");
            }
        }
        String typ = text(header, "typ");
        if (typ == null || !(typ.equalsIgnoreCase(TYP) || typ.equalsIgnoreCase("application/" + TYP))) {
            throw new PartnerAuthException("typ");
        }
        String kid = text(header, "kid");
        VoucherPartnerKey key = kid == null ? null : keys.findByKid(kid).orElse(null);
        if (key == null) {
            throw new PartnerAuthException("unknown_kid");
        }
        if (key.getStatus() != VoucherPartnerKey.Status.ACTIVE) {
            throw new PartnerAuthException("revoked_key");
        }
        if (!key.getAlg().name().equals(text(header, "alg"))) {
            throw new PartnerAuthException("alg");
        }
        VoucherPartner partner = partners.findById(key.getPartnerId()).orElse(null);
        if (partner == null || partner.getStatus() != VoucherPartner.Status.ACTIVE) {
            throw new PartnerAuthException("partner_inactive");
        }
        if (!IpLiterals.allowed(partner.getAllowedIps(), clientIp)) {
            throw new PartnerAuthException("ip");
        }
        if (!signatureValid(key, headerPart + "." + payloadPart, signaturePart)) {
            throw new PartnerAuthException("bad_signature");
        }

        JsonNode claims = readObject(payloadPart, "payload");
        if (!partner.getIssuer().equals(text(claims, "iss"))) {
            throw new PartnerAuthException("iss");
        }
        if (props.audience().isBlank() || !props.audience().equals(singleAudience(claims.get("aud")))) {
            throw new PartnerAuthException("aud");
        }
        if (expectedOp == null || !expectedOp.equals(text(claims, "op"))) {
            throw new PartnerAuthException("op");
        }
        String jti = text(claims, "jti");
        if (jti == null || !JTI.matcher(jti).matches()) {
            throw new PartnerAuthException("jti");
        }
        long now = clock.instant().getEpochSecond();
        long skew = props.maxClockSkew().toSeconds();
        Long iat = epochSeconds(claims.get("iat"));
        Long exp = epochSeconds(claims.get("exp"));
        if (iat == null || exp == null || iat > now + skew || exp < now - skew
                || exp <= iat || exp - iat > props.maxLifetime().toSeconds()) {
            throw new PartnerAuthException("timing");
        }
        JsonNode body = claims.get("body");

        recordJti(partner, jti, Duration.ofSeconds(Math.max(0, exp - now)).plus(REPLAY_GRACE));
        return new PartnerRequest(partner, kid, expectedOp, jti,
                body == null || body.isNull() ? null : body);
    }

    private boolean signatureValid(VoucherPartnerKey key, String signingInput, String signaturePart) {
        try {
            PublicKey publicKey = parsedKeys.computeIfAbsent(key.getKid() + "|" + key.getPublicKeyPem(),
                    ignored -> PartnerSigningKeys.parse(key.getPublicKeyPem(), key.getAlg()));
            byte[] signature = Base64.getUrlDecoder().decode(signaturePart);
            if (key.getAlg() == VoucherPartnerKey.Alg.ES256 && signature.length != 64) {
                return false;
            }
            Signature verifier = Signature.getInstance(PartnerSigningKeys.jcaAlgorithm(key.getAlg()));
            verifier.initVerify(publicKey);
            verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signature);
        } catch (Exception invalid) {
            return false;
        }
    }

    private void recordJti(VoucherPartner partner, String jti, Duration ttl)
            throws PartnerAuthException, PartnerAuthException.ReplayCheckUnavailable {
        if (!jtiStore.firstUse("loyalty:partner:jti:" + partner.getId() + ":" + jti, ttl)) {
            throw new PartnerAuthException("replay");
        }
    }

    private static JsonNode readObject(String base64url, String what) throws PartnerAuthException {
        try {
            JsonNode node = STRICT.readTree(Base64.getUrlDecoder().decode(base64url));
            if (node == null || !node.isObject()) {
                throw new PartnerAuthException(what);
            }
            return node;
        } catch (PartnerAuthException e) {
            throw e;
        } catch (Exception unreadable) {
            throw new PartnerAuthException(what);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** {@code aud} as one string: a string, or an array holding exactly one. */
    private static String singleAudience(JsonNode aud) {
        if (aud == null) {
            return null;
        }
        if (aud.isTextual()) {
            return aud.asText();
        }
        if (aud.isArray() && aud.size() == 1 && aud.get(0).isTextual()) {
            return aud.get(0).asText();
        }
        return null;
    }

    private static Long epochSeconds(JsonNode n) {
        if (n == null || !n.isNumber()) {
            return null;
        }
        if (n.isIntegralNumber() && n.canConvertToLong()) {
            return n.asLong();
        }
        // A whole number written with a fraction (1792745643.0) is still a time.
        try {
            return n.decimalValue().longValueExact();
        } catch (ArithmeticException notWhole) {
            return null;
        }
    }
}
