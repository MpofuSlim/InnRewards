package com.innbucks.loyaltyservice.testsupport;

import io.jsonwebtoken.Jwts;

import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints HS256 JWTs that loyalty-service's {@code JwtFilter} will accept. Mirrors
 * the claim shape produced by user-service's {@code JwtUtil#generateToken} so
 * what the tests sign is what production signs.
 *
 * <p>Pass the same secret the service is configured with — for tests we
 * use the value from {@code application-test.yaml}'s {@code jwt.secret}.
 *
 * <p>Convenience static factories cover the common test personas. Use the
 * builder for one-offs:
 * <pre>
 * String token = TestJwtFactory.builder("shopkeep@example.com")
 *     .role("SHOP_ADMIN")
 *     .merchantId(merchantId)
 *     .shopId(shopId)
 *     .sign(secret);
 * </pre>
 */
public final class TestJwtFactory {

    private TestJwtFactory() {}

    public static Builder builder(String email) {
        return new Builder(email);
    }

    /** SUPER_ADMIN with verified=true, tier=4, no tenant scoping. */
    public static String superAdmin(String secret) {
        return builder("super@test.local").role("SUPER_ADMIN").tier(4).verified(true).sign(secret);
    }

    /** MERCHANT_ADMIN. Their JWT intentionally carries NO merchantId — they pass it in the body. */
    public static String merchantAdmin(String email, String secret) {
        return builder(email).role("MERCHANT_ADMIN").sign(secret);
    }

    /** SHOP_ADMIN scoped to the given merchant + shop. */
    public static String shopAdmin(String email, UUID merchantId, UUID shopId, String secret) {
        return builder(email).role("SHOP_ADMIN").merchantId(merchantId).shopId(shopId).sign(secret);
    }

    /** SHOP_USER scoped to the given merchant + shop. */
    public static String shopUser(String email, UUID merchantId, UUID shopId, String secret) {
        return builder(email).role("SHOP_USER").merchantId(merchantId).shopId(shopId).sign(secret);
    }

    /** CUSTOMER with the given tier. */
    public static String customer(String email, int tier, String secret) {
        return builder(email).role("CUSTOMER").tier(tier).verified(true).sign(secret);
    }

    public static final class Builder {
        private final String email;
        private List<String> roles = List.of();
        private List<String> services = List.of();
        private int tier = 0;
        private boolean verified = false;
        private String phoneNumber;
        private UUID merchantId;
        private UUID shopId;
        private UUID userId;
        // Default: token is valid for 1 hour from now.
        private long ttlMillis = 3_600_000L;

        private Builder(String email) {
            this.email = email;
        }

        public Builder role(String role) {
            this.roles = role == null ? List.of() : List.of(role);
            return this;
        }

        public Builder roles(List<String> roles) {
            this.roles = roles == null ? List.of() : List.copyOf(roles);
            return this;
        }

        public Builder services(List<String> services) {
            this.services = services == null ? List.of() : List.copyOf(services);
            return this;
        }

        public Builder tier(int tier) {
            this.tier = tier;
            return this;
        }

        public Builder verified(boolean verified) {
            this.verified = verified;
            return this;
        }

        public Builder phoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
            return this;
        }

        public Builder merchantId(UUID merchantId) {
            this.merchantId = merchantId;
            return this;
        }

        public Builder shopId(UUID shopId) {
            this.shopId = shopId;
            return this;
        }

        /** Sets the caller's stable cross-service UUID. Emitted as the
         *  {@code userUuid} claim — the one user-service mints and
         *  loyalty-service keys tenant membership on. */
        public Builder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        /** Token issued in the past with expiry also in the past — for expired-token tests. */
        public Builder expired() {
            this.ttlMillis = -60_000L;
            return this;
        }

        public String sign(String secret) {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            var builder = Jwts.builder()
                    .subject(email)
                    .issuer("innbucks-ticketing")
                    .audience().add("innbucks-app").and()
                    .claim("roles", roles)
                    .claim("services", services)
                    .claim("tier", tier)
                    .claim("verified", verified);
            if (phoneNumber != null && !phoneNumber.isBlank()) {
                builder.claim("phoneNumber", phoneNumber);
            }
            if (merchantId != null) {
                builder.claim("merchantId", merchantId.toString());
            }
            if (shopId != null) {
                builder.claim("shopId", shopId.toString());
            }
            if (userId != null) {
                builder.claim("userUuid", userId.toString());
            }
            long now = System.currentTimeMillis();
            return builder
                    .issuedAt(new Date(now - 1_000L))
                    .expiration(new Date(now + ttlMillis))
                    .signWith(key, Jwts.SIG.HS256)
                    .compact();
        }
    }
}
