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

    /** Length of a newly issued voucher code — see {@link #randomNumericVoucherCode()}. */
    public static final int CODE_DIGITS = 16;

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
     * The voucher code issued since the switch to numeric codes: sixteen
     * digits, the first never {@code 0} — {@code 9087876598764567}. People see
     * it GROUPED ({@code 9087 8765 9876 4567}, {@link
     * com.innbucks.loyaltyservice.util.VoucherCodes#display}) and may type it
     * back grouped; lookups normalise first. It is stored and served raw.
     *
     * <p><b>Why sixteen.</b> 9×10¹⁵ codes (~53 bits), against ~33 for ten
     * digits and ~40 for twelve, and within a hundredfold of the old
     * 12-character alphanumeric code's ~60. Grouping in fours is what makes the
     * length readable. Guessing gains a CUSTOMER nothing — redemption is
     * refused unless the voucher is assigned to their own phone — so the
     * exposure is a staff till (which may redeem any of its merchant's codes
     * and which the fraud velocity rule deliberately never auto-blocks) and the
     * code-only public redeem wherever the public test surface is enabled. At
     * that size neither is a realistic guessing target; every miss still lands
     * in {@code fraud_attempts} as {@code INVALID_CODE}.
     *
     * <p><b>No leading zero, on purpose.</b> A spreadsheet reads
     * {@code 0123…} as a number and drops the zero, turning a valid code into
     * one that never redeems. (Sixteen digits also exceeds a spreadsheet's 15
     * significant digits, which corrupts the LAST digit of an ungrouped code —
     * the reason exports carry the grouped form, which is text.)
     *
     * <p>Codes already issued in the old format stay valid: lookup is an exact
     * match on the stored string, and nothing re-codes existing rows.
     */
    public static String randomNumericVoucherCode() {
        StringBuilder sb = new StringBuilder(CODE_DIGITS);
        sb.append((char) ('1' + RANDOM.nextInt(9)));
        for (int i = 1; i < CODE_DIGITS; i++) {
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
