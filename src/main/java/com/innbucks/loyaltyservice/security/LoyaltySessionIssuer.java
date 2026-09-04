package com.innbucks.loyaltyservice.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hands a customer a loyalty session once they have PROVED the phone it is
 * scoped to — the piece that makes a registration immediately useful.
 *
 * <h2>The gap this closes</h2>
 * Registration (V40–V42) records that a phone's owner proved they hold it,
 * which makes their accounts spendable. But "spendable" and "able to call the
 * spend endpoints" were different things: loyalty's authenticated
 * transfer/redeem surface needs a bearer token, and the only source of one was
 * ticketing's OTP verify. So a customer who registered through their InnBucks
 * login was activated and still had to receive an SMS before they could act —
 * a second authentication for a phone they had just proved.
 *
 * <p>Now the proof and the session come from the same call.
 *
 * <h2>Only for proofs the CUSTOMER performed</h2>
 * A session is issued only in the modes where the caller IS the customer's
 * device — {@code innbucks} and {@code veengu}. It is deliberately withheld in
 * {@code assertion} and {@code key} mode, where the caller is a partner's
 * BACKEND: those modes prove a phone on the customer's behalf, and handing that
 * backend a live customer session would give it the ability to act as any
 * customer it registers. Registration on behalf of someone is a legitimate
 * thing for a partner to do; holding their session is not.
 *
 * <h2>Its own scope marker</h2>
 * {@link #LOYALTY_SESSION_SCOPE} is distinct from user-service's
 * {@code loyalty-otp} on purpose. Both mean "phone-scoped, loyalty-only
 * session", and {@code JwtFilter} accepts either — but keeping them separate
 * means an incident affecting one proof channel can be scoped to the tokens it
 * minted, and the marker stays an honest record of how the phone was proved
 * rather than a value named after a flow that did not happen.
 */
@Component
@RequiredArgsConstructor
public class LoyaltySessionIssuer {

    /**
     * The {@code services} marker on a session this service minted, from a
     * proof the customer performed themselves.
     *
     * <p>Unlike user-service's {@code loyalty-otp}, this constant and its
     * consumer live in the SAME repository — {@link JwtFilter} reads it
     * directly, so the cross-repo drift risk that one carries does not apply.
     */
    public static final String LOYALTY_SESSION_SCOPE = "loyalty-session";

    private final JwtUtil jwtUtil;

    /**
     * Twelve hours, matching user-service's OTP session so the app sees one
     * lifetime whichever way its customer proved their phone.
     *
     * <p>The TTL <b>is</b> the revocation story. These tokens carry no
     * {@code userId}, so the fleet's tokenVersion denylist — which is keyed by
     * user UUID — cannot reach them. Shortening this is the only lever against
     * a stolen session; lengthening it should be a deliberate decision, not a
     * convenience.
     */
    @Value("${loyalty.session.ttl-seconds:43200}")
    private long ttlSeconds = 43200;

    /**
     * @param e164Phone the phone just proved, in the canonical spelling that was
     *                  proved — never a second phone field the caller also sent,
     *                  or the token would assert something the proof did not.
     */
    public String issue(String e164Phone) {
        if (e164Phone == null || e164Phone.isBlank()) {
            throw new IllegalArgumentException("a phone number is required to issue a loyalty session");
        }
        return jwtUtil.generateLoyaltySessionToken(e164Phone, LOYALTY_SESSION_SCOPE, ttlSeconds * 1000L);
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
