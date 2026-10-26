package com.innbucks.loyaltyservice.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Signs partner API requests the way the middleware will (V62): a compact JWS,
 * ES256 or RS256, over the header and claims given. For tests only; the private
 * keys are generated per test.
 */
public final class PartnerJws {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PartnerJws() {
    }

    public static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair rsaKeyPair(int bits) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(bits);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pem(PublicKey key) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }

    /** Standard claims for {@code op}, valid now, with a fresh jti. */
    public static Map<String, Object> claims(String issuer, String audience, String op, Object body) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", issuer);
        c.put("aud", audience);
        c.put("op", op);
        c.put("jti", UUID.randomUUID().toString());
        c.put("iat", now);
        c.put("exp", now + 60);
        c.put("body", body);
        return c;
    }

    public static Map<String, Object> header(String alg, String kid) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("alg", alg);
        h.put("kid", kid);
        h.put("typ", "voucher-request+jwt");
        return h;
    }

    public static String sign(Map<String, Object> header, Map<String, Object> claims, PrivateKey key) {
        try {
            return sign(JSON.writeValueAsBytes(header), JSON.writeValueAsBytes(claims), key,
                    String.valueOf(header.get("alg")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signs raw header and payload bytes, for shapes a Map cannot express (duplicate keys). */
    public static String sign(byte[] header, byte[] payload, PrivateKey key, String alg) {
        try {
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            String input = enc.encodeToString(header) + "." + enc.encodeToString(payload);
            Signature s = Signature.getInstance("RS256".equals(alg) ? "SHA256withRSA" : "SHA256withECDSAinP1363Format");
            s.initSign(key);
            s.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + enc.encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
