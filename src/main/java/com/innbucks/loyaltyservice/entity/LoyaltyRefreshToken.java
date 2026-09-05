package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One issued loyalty refresh token (V43) — the credential that lets a customer's
 * device renew its 12h loyalty session without obtaining a fresh phone proof.
 *
 * <p><b>Why a second credential rather than a longer access token.</b> The
 * access token {@code LoyaltySessionIssuer} mints carries no {@code userId}, so
 * the fleet's tokenVersion denylist cannot reach it and its TTL is the only
 * thing that ever ends it. Lengthening that TTL — or letting the token renew
 * itself — turns it into a bearer credential nothing can withdraw, and a stolen
 * copy would renew alongside the legitimate one indefinitely with no moment at
 * which the theft becomes visible. This row is the withdrawable half: revocation
 * is an UPDATE, and rotation makes a second holder detectable.
 *
 * <p><b>Only the hash is stored.</b> {@link #tokenHash} is the SHA-256 of the
 * 32-random-byte token, so a database read yields nothing a caller could
 * present. Bare SHA-256 rather than a keyed HMAC is deliberate and matches the
 * fleet's rule: keyed hashing exists for LOW-entropy secrets (a six-digit OTP,
 * a voucher code) whose whole space can be enumerated through a fast hash. A
 * full-entropy random token has no such space.
 *
 * <p><b>The chain is the unit of revocation.</b> Each refresh retires the
 * presented row ({@link #usedAt}) and issues its successor with the same
 * {@link #chainId}. Presenting a row that is already used means two parties hold
 * credentials from the same chain, and there is no way to tell which is the
 * customer — so the whole chain is revoked and the phone is re-proved. That
 * detection is the thing a self-renewing access token could never offer.
 */
@Entity
@Table(name = "loyalty_refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyRefreshToken {

    @Id
    @Column(nullable = false)
    private UUID id = UUID.randomUUID();

    /** Lowercase-hex SHA-256 of the token. The token itself is never stored. */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** E.164 with the leading {@code +} — the spelling {@code phone_registrations}
     *  keys on, so the two join with no drift. */
    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    /** The family this token belongs to. Constant across every rotation. */
    @Column(name = "chain_id", nullable = false)
    private UUID chainId;

    /**
     * The {@code services} scope marker of the access token that STARTED this
     * chain — {@code loyalty-otp} (ticketing's OTP verify) or
     * {@code loyalty-session} (this service's own issuer). Carried forward
     * unchanged by every rotation so a months-old chain still names the proof
     * channel it came from.
     */
    @Column(name = "origin_scope", nullable = false, length = 40)
    private String originScope;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    /** Sliding: each rotation issues a successor with a fresh window. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Stamped when this row is rotated. Non-null means spent. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 64)
    private String revokedReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Usable exactly once, and only while unspent, unrevoked and in date.
     *
     * <p>Deliberately does NOT distinguish the three failures — the caller maps
     * all of them onto one opaque answer, because telling a holder whether a
     * token was revoked, spent or merely stale is telling them something about
     * an account they may not own.
     */
    public boolean isLive(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
