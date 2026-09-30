package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.exception.LoyaltyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * The support agent acting on this request, as the shared contract defines it:
 * {@code uuid} is the token's {@code userUuid} claim (falling back to the
 * subject for a token without one), {@code login} is the subject — the login
 * e-mail. Both are stamped on every note, message and activity row.
 *
 * <p>Read from the security context the {@link JwtFilter} built, never from a
 * request: an agent cannot claim to be someone else.
 *
 * @param uuid     at most {@link #MAX_UUID} characters (the column width)
 * @param login    the subject, or null
 * @param userUuid the {@code userUuid} claim as a UUID, or null when the token
 *                 carries none — see {@link #requireUserUuid()}
 */
public record SupportAgent(String uuid, String login, UUID userUuid) {

    /** Width of every {@code agent_uuid} column (V54). */
    public static final int MAX_UUID = 64;

    public static SupportAgent current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            // Unreachable behind @PreAuthorize(hasAuthority(...)); a support write
            // with nobody to attribute it to must not happen quietly.
            throw LoyaltyException.forbidden("agent_identity_required",
                    "This action needs a signed-in agent.");
        }
        UUID userUuid = CallerDetails.currentUserId();
        String login = auth.getName();
        String uuid = userUuid != null ? userUuid.toString() : login;
        if (uuid.length() > MAX_UUID) {
            uuid = uuid.substring(0, MAX_UUID);
        }
        return new SupportAgent(uuid, login, userUuid);
    }

    /**
     * The agent's account UUID, for an action that records it in a UUID column
     * ({@code loyalty_transactions.posted_by}) and keys a limit on it — the
     * per-operator daily adjustment cap sums by {@code posted_by}, so an
     * adjustment with no operator could not be counted against anyone. Every
     * token user-service mints for staff carries the claim; one that does not is
     * refused rather than allowed to post uncounted.
     */
    public UUID requireUserUuid() {
        if (userUuid == null) {
            throw LoyaltyException.forbidden("agent_identity_required",
                    "This action needs an agent token carrying a userUuid claim. Sign in again.");
        }
        return userUuid;
    }
}
