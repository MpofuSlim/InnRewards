package com.innbucks.loyaltyservice.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signer used for voucher codes and QR tokens. Constant-time
 * comparison guards against timing attacks during signature verification.
 */
public final class CryptoSigner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    public CryptoSigner(String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    public boolean verify(String payload, String signature) {
        if (signature == null) return false;
        String expected = sign(payload);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The voucher code issued since the switch to numeric codes: ten digits, the
     * first never {@code 0}. Easy to read aloud at a till and to type on a phone
     * keypad, which the 12-character alphanumeric codes were not.
     *
     * <p><b>No leading zero, on purpose.</b> Vouchers are exported to CSV and
     * opened in spreadsheets, which read {@code 0123456789} as the number
     * {@code 123456789} and drop the zero — turning a valid code into one that
     * never redeems, silently. First digit 1–9 leaves 9×10⁹ codes.
     *
     * <p><b>The trade-off.</b> That is ~33 bits against the old code's ~60
     * (12 symbols of 32). Guessing gains a CUSTOMER nothing — redemption is
     * refused unless the voucher is assigned to their own phone — so the
     * exposure is a staff till, which may redeem any of its merchant's codes and
     * which the fraud velocity rule deliberately never auto-blocks. Every miss
     * still lands in {@code fraud_attempts} as {@code INVALID_CODE}.
     *
     * <p>Codes already issued in the old format stay valid: lookup is an exact
     * match on the stored string, and nothing re-codes existing rows.
     */
    public static String randomNumericVoucherCode() {
        StringBuilder sb = new StringBuilder(10);
        sb.append((char) ('1' + RANDOM.nextInt(9)));
        for (int i = 1; i < 10; i++) {
            sb.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return sb.toString();
    }

    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
