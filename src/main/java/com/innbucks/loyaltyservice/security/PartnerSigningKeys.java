package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.VoucherPartnerKey;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * A partner's public signing key from PEM (V62). Accepts the key on one line with
 * literal {@code \n} escapes, as an env value or a JSON field carries it, the
 * same way every other PEM parser in this service does. Anything else that is
 * not Base64 fails, on purpose.
 */
public final class PartnerSigningKeys {

    private PartnerSigningKeys() {
    }

    /**
     * @throws IllegalArgumentException when the PEM is not an X.509
     *         SubjectPublicKeyInfo key of the kind {@code alg} needs: P-256 for
     *         ES256, RSA of at least 2048 bits for RS256
     */
    public static PublicKey parse(String pem, VoucherPartnerKey.Alg alg) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("The public key is empty.");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(
                    pem.replace("\\n", "").replace("\\r", "")
                            .replaceAll("-----BEGIN [^-]+-----", "")
                            .replaceAll("-----END [^-]+-----", "")
                            .replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The public key is not a PEM (Base64) key.");
        }
        try {
            return switch (alg) {
                case ES256 -> {
                    PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
                    if (!(key instanceof ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 256) {
                        throw new IllegalArgumentException("ES256 needs a P-256 (prime256v1) public key.");
                    }
                    yield key;
                }
                case RS256 -> {
                    PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                    if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048) {
                        throw new IllegalArgumentException("RS256 needs an RSA public key of at least 2048 bits.");
                    }
                    yield key;
                }
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("The public key is not a valid " + alg
                    + " public key (expected an X.509 SubjectPublicKeyInfo PEM).");
        }
    }

    /** The JCA signature algorithm for a JWS {@code alg}. ES256 signatures are R||S (64 bytes). */
    static String jcaAlgorithm(VoucherPartnerKey.Alg alg) {
        return switch (alg) {
            case ES256 -> "SHA256withECDSAinP1363Format";
            case RS256 -> "SHA256withRSA";
        };
    }
}
