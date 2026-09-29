package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.util.VoucherCodes;

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
    public static final int CODE_DIGITS = VoucherCodes.NUMERIC_LENGTH;

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
     * digits — a first digit 1–9, fourteen random digits and a check digit —
     * e.g. {@code 9087876598764566}. People see it grouped
     * ({@code 9087 8765 9876 4566}) and may type it back grouped; see
     * {@link com.innbucks.loyaltyservice.util.VoucherCodes} for both rules and
     * for the check digit, which catches typos and keeps every code from being
     * a valid payment-card number. It is stored and served raw.
     *
     * <p><b>Why sixteen.</b> 9×10¹⁴ possible codes (~50 bits; the check digit
     * adds no randomness), against ~33 bits for ten digits and ~40 for twelve,
     * and within a few thousandfold of the old 12-character alphanumeric
     * code's ~60. Grouping in fours is what makes the length readable.
     * Guessing gains a CUSTOMER nothing — redemption is refused unless the
     * voucher is assigned to their own phone — so the exposure is a staff till
     * (which may redeem any of its merchant's codes, and which the fraud
     * velocity rule deliberately never auto-blocks) and the code-only public
     * redeem wherever the public test surface is enabled. At this size neither
     * is a realistic guessing target.
     *
     * <p><b>No leading zero, on purpose.</b> A spreadsheet reads {@code 0123…}
     * as a number and drops the zero, turning a valid code into one that never
     * redeems.
     *
     * <p>Codes already issued in the old format stay valid: a lookup matches the
     * NORMALISED input against the stored code (and the input exactly as typed,
     * as a fallback), and nothing re-codes existing rows.
     */
    public static String randomNumericVoucherCode() {
        StringBuilder sb = new StringBuilder(CODE_DIGITS);
        sb.append((char) ('1' + RANDOM.nextInt(9)));
        for (int i = 1; i < CODE_DIGITS - 1; i++) {
            sb.append((char) ('0' + RANDOM.nextInt(10)));
        }
        sb.append((char) ('0' + VoucherCodes.checkDigit(sb)));
        return sb.toString();
    }

    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
