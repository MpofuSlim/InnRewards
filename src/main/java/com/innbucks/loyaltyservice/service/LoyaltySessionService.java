package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyRefreshToken;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyRefreshTokenRepository;
import com.innbucks.loyaltyservice.security.LoyaltySessionIssuer;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and renews loyalty sessions (V43) — the half of the session story the
 * access token could not carry.
 *
 * <h2>What this fixes</h2>
 * {@link LoyaltySessionIssuer} mints a 12h phone-scoped access token, and that
 * TTL was doing double duty as the lifetime AND the whole revocation story
 * (the token has no {@code userId}, so the fleet's tokenVersion denylist cannot
 * reach it). The cost of that landed on the customer: registration is a
 * PERMANENT phone-level fact (V40), so a customer proves their phone once — but
 * with nothing able to renew the session, the app had to obtain a new proof
 * every twelve hours, and the only live proof channel is an SMS OTP. One SMS
 * per customer for life became one SMS per twelve hours, per device.
 *
 * <h2>Why not simply renew the access token from itself</h2>
 * Because then the access token IS the refresh credential, and a stolen copy
 * renews forever alongside the legitimate one. Both keep working, so there is
 * no moment at which the theft is observable. Rotation through a separate,
 * server-recorded credential is what makes a second holder detectable at all:
 * each refresh retires the presented row, so presenting a spent one means two
 * parties hold credentials from the same chain.
 *
 * <h2>The three operations</h2>
 * <ul>
 *   <li>{@link #start} — a customer holding a LIVE, phone-proved access token
 *       trades it for a chain. This is the bootstrap; it mints no proof and
 *       grants nothing the caller did not already hold.</li>
 *   <li>{@link #refresh} — rotate. Retires the presented row, issues its
 *       successor, and hands back a fresh access token.</li>
 *   <li>{@link #signOut} — revoke the chain. Sign-out becomes an event rather
 *       than a wait.</li>
 * </ul>
 *
 * <h2>What a refresh is NOT allowed to do</h2>
 * It never re-proves a phone and never registers one. A refresh is only ever
 * the continuation of a proof someone already performed, which is why
 * {@link #refresh} re-asks {@link UserService#isPhoneRegistered} on every call:
 * an operator who revokes a registration (the V40 tombstone) has signed that
 * customer out, and a chain that kept minting access tokens past that point
 * would quietly outlive the decision.
 */
@Service
@Slf4j
public class LoyaltySessionService {

    /**
     * Human-visible prefix on the opaque token. It is not a credential and is
     * not parsed — it exists so a leaked value is identifiable at a glance in a
     * log, a bug report or a secret scanner, instead of reading as anonymous
     * base64.
     */
    static final String TOKEN_PREFIX = "LRT-";

    /**
     * 32 bytes of {@link SecureRandom}. Full entropy is what makes the bare
     * SHA-256 at rest correct rather than a shortcut — the fleet's keyed-HMAC
     * rule exists for LOW-entropy secrets (a six-digit OTP, a voucher code)
     * whose whole space can be walked through a fast hash. There is no such
     * space here.
     */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LoyaltyRefreshTokenRepository tokens;
    private final LoyaltySessionIssuer sessionIssuer;
    private final UserService userService;
    private final LoyaltyMetrics metrics;

    /**
     * How long an unused refresh token stays usable. SLIDING — every rotation
     * issues a successor with a fresh window — so an app in ordinary use never
     * re-proves, which is the entire point. An app untouched for the whole
     * window ages out and the customer proves the phone once more, which is the
     * bound that stops an abandoned device being a permanent credential.
     *
     * <p>Ninety days is chosen to sit well beyond any normal gap in use while
     * still being a real expiry. Shorten it to cut the value of a stolen device;
     * lengthening it should be a deliberate decision, not a convenience.
     */
    private final long refreshTtlDays;

    public LoyaltySessionService(LoyaltyRefreshTokenRepository tokens,
                                 LoyaltySessionIssuer sessionIssuer,
                                 UserService userService,
                                 LoyaltyMetrics metrics,
                                 @Value("${loyalty.session.refresh-ttl-days:90}") long refreshTtlDays) {
        this.tokens = tokens;
        this.sessionIssuer = sessionIssuer;
        this.userService = userService;
        this.metrics = metrics;
        this.refreshTtlDays = refreshTtlDays;
    }

    /**
     * What every one of the three operations hands back: a short-lived access
     * token for the loyalty API, and the refresh token that will renew it.
     *
     * <p>{@code phoneNumber} is included because on a refresh the caller sends
     * no phone at all — the row the presented token resolves to is the only
     * thing that knows whose session this is, and an app restarted from cold
     * storage would otherwise hold a live session it could not name.
     */
    public record Session(String phoneNumber,
                          String accessToken,
                          long expiresInSeconds,
                          String refreshToken,
                          long refreshExpiresInSeconds) {}

    /**
     * Trades a live, phone-proved access token for a refresh chain.
     *
     * <p><b>This grants no new authority.</b> The caller already holds a session
     * for this phone; the exchange only makes that session renewable. The
     * controller is what establishes that the caller holds one — it accepts the
     * call solely from a token carrying a phone-scoped scope marker, so a staff
     * token cannot walk this path.
     *
     * @param e164Phone the phone from the caller's OWN token claim, never a
     *                  request field — a body phone would let any holder of one
     *                  valid session open a chain on someone else's number.
     * @param originScope the presented token's scope marker, recorded on the
     *                  chain so it still names its proof channel months later.
     */
    @Transactional
    public Session start(String e164Phone, String originScope) {
        UUID chainId = UUID.randomUUID();
        String refreshToken = persistNewToken(chainId, e164Phone, originScope);
        metrics.incLoyaltySession("started");
        log.info("Loyalty session chain started phone={} chain={} origin={}",
                MsisdnMasking.mask(e164Phone), chainId, originScope);
        return session(e164Phone, refreshToken);
    }

    /**
     * Rotates a refresh token: retires the presented row, issues its successor
     * in the same chain, and returns a fresh access token.
     *
     * <h2>Reuse detection</h2>
     * A row that is already {@code usedAt} is not "not found" — it is a second
     * party presenting a credential the legitimate device already spent. Nothing
     * distinguishes the two holders, so the whole chain is revoked and both are
     * signed out; the customer proves their phone again. Treating this as an
     * ordinary rejection would leave the attacker's copy of the CURRENT token
     * working.
     *
     * <p>Every other failure — unknown, revoked, expired — answers the same
     * opaque 401. Which one it was is logged, never returned: telling a holder
     * that a token was revoked rather than unknown tells them something about an
     * account they may not own.
     */
    @Transactional
    public Session refresh(String presentedToken) {
        Instant now = Instant.now();
        LoyaltyRefreshToken row = lookup(presentedToken)
                .orElseThrow(() -> {
                    metrics.incLoyaltySessionRejected("unknown");
                    return rejected();
                });

        if (row.getUsedAt() != null) {
            // The signal this whole design exists to produce. Revoke the family,
            // not just this row: the attacker holds the successor too.
            int revoked = tokens.revokeChain(row.getChainId(), now, "reuse_detected");
            metrics.incLoyaltySessionRejected("reuse_detected");
            log.warn("Loyalty refresh token REUSE detected — chain revoked phone={} chain={} rows={}",
                    MsisdnMasking.mask(row.getPhoneNumber()), row.getChainId(), revoked);
            throw rejected();
        }
        if (row.getRevokedAt() != null) {
            metrics.incLoyaltySessionRejected("revoked");
            log.info("Loyalty refresh refused: revoked chain phone={} chain={} reason={}",
                    MsisdnMasking.mask(row.getPhoneNumber()), row.getChainId(), row.getRevokedReason());
            throw rejected();
        }
        if (!row.isLive(now)) {
            metrics.incLoyaltySessionRejected("expired");
            log.info("Loyalty refresh refused: expired chain phone={} chain={}",
                    MsisdnMasking.mask(row.getPhoneNumber()), row.getChainId());
            throw rejected();
        }

        // A refresh continues a proof; it never performs one. If the phone's
        // registration has been revoked since the chain started, the operator
        // has signed this customer out — a chain that kept minting access
        // tokens past that point would quietly outlive the decision.
        if (!userService.isPhoneRegistered(row.getPhoneNumber())) {
            tokens.revokeChain(row.getChainId(), now, "registration_revoked");
            metrics.incLoyaltySessionRejected("registration_revoked");
            log.warn("Loyalty refresh refused: phone no longer registered — chain revoked phone={} chain={}",
                    MsisdnMasking.mask(row.getPhoneNumber()), row.getChainId());
            throw rejected();
        }

        row.setUsedAt(now);
        tokens.save(row);
        String successor = persistNewToken(row.getChainId(), row.getPhoneNumber(), row.getOriginScope());
        metrics.incLoyaltySession("refreshed");
        return session(row.getPhoneNumber(), successor);
    }

    /**
     * Ends the chain the presented token belongs to.
     *
     * <p>Deliberately idempotent and silent about what it found: an unknown or
     * already-dead token is a successful sign-out, because the outcome the
     * caller wanted — this credential no longer works — is true either way.
     * Answering otherwise would turn sign-out into an oracle for whether a token
     * exists.
     */
    @Transactional
    public void signOut(String presentedToken) {
        lookup(presentedToken).ifPresent(row -> {
            int revoked = tokens.revokeChain(row.getChainId(), Instant.now(), "signed_out");
            metrics.incLoyaltySession("signed_out");
            log.info("Loyalty session signed out phone={} chain={} rows={}",
                    MsisdnMasking.mask(row.getPhoneNumber()), row.getChainId(), revoked);
        });
    }

    /**
     * Revokes every chain of a phone — "sign this customer out everywhere".
     *
     * <p>The lever the access token's TTL was standing in for. Not reachable
     * from the customer API by design: it is the operator-side companion to
     * revoking the phone's registration, and it is called from there rather than
     * exposed as an endpoint anyone could aim at a number.
     */
    @Transactional
    public int revokeAllForPhone(String e164Phone, String reason) {
        int revoked = tokens.revokeAllForPhone(e164Phone, Instant.now(), reason);
        if (revoked > 0) {
            metrics.incLoyaltySession("revoked");
            log.warn("Loyalty sessions revoked for phone={} rows={} reason={}",
                    MsisdnMasking.mask(e164Phone), revoked, reason);
        }
        return revoked;
    }

    private Session session(String e164Phone, String refreshToken) {
        return new Session(
                e164Phone,
                sessionIssuer.issue(e164Phone),
                sessionIssuer.ttlSeconds(),
                refreshToken,
                Duration.ofDays(refreshTtlDays).toSeconds());
    }

    /** Generates a token, stores only its hash, and returns the token ONCE. */
    private String persistNewToken(UUID chainId, String e164Phone, String originScope) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        Instant now = Instant.now();
        LoyaltyRefreshToken row = new LoyaltyRefreshToken();
        row.setTokenHash(hash(token));
        row.setPhoneNumber(e164Phone);
        row.setChainId(chainId);
        row.setOriginScope(originScope);
        row.setIssuedAt(now);
        row.setExpiresAt(now.plus(Duration.ofDays(refreshTtlDays)));
        tokens.save(row);
        return token;
    }

    private Optional<LoyaltyRefreshToken> lookup(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(hash(presentedToken.trim()));
    }

    /**
     * SHA-256, lowercase hex. Bare rather than keyed because the input is 32
     * random bytes — see the class javadoc.
     */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * One answer for every refusal. The FE branches on the status, never on a
     * reason — and there is only one correct client behaviour anyway: obtain a
     * fresh phone proof.
     */
    private static LoyaltyException rejected() {
        return LoyaltyException.unauthorized("SESSION_REFRESH_REJECTED",
                "This session can no longer be renewed. Please sign in again.");
    }
}
