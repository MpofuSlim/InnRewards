-- A loyalty session that can outlive its 12h access token, without becoming an
-- unrevocable bearer credential.
--
-- THE PROBLEM. `LoyaltySessionIssuer` mints a phone-scoped access token with a
-- 12h TTL and no refresh path, and that TTL was doing double duty: it was the
-- lifetime AND the entire revocation story, because the token carries no
-- `userId` claim and so the fleet's tokenVersion denylist — keyed by user UUID
-- — cannot reach it. The cost lands on the customer. Registration is a
-- PERMANENT phone-level fact (V40), so a customer proves their phone once; but
-- with no way to renew the session, every 12h the app has to obtain a new
-- proof, and the only live proof channel is ticketing's SMS OTP. One SMS per
-- customer for life turns into one SMS every twelve hours, per device.
--
-- WHY NOT JUST LENGTHEN THE TTL, OR LET A TOKEN RENEW ITSELF. Both make the
-- access token a long-lived bearer credential that nothing can withdraw. A
-- self-renewing token is worse than a long one: a stolen copy renews alongside
-- the legitimate one forever, and because both copies keep working there is no
-- moment at which the theft is observable.
--
-- THE MODEL. A second, opaque credential — the refresh token — held only by the
-- customer's device and recorded here as a row. It is what the access token
-- could not be:
--
--   * REVOCABLE. Killing a session is an UPDATE on a row, not a wait for a TTL.
--   * ROTATING. Each refresh retires the presented row and issues its
--     successor in the same chain, so a captured token has a bounded useful
--     life even before anyone notices.
--   * OBSERVABLE. Because rotation retires the old row, presenting an already-
--     used token is a signal that two parties hold the same credential. That is
--     the theft detection a self-renewing access token can never have: the
--     whole chain is revoked and the customer re-proves.
--
-- The access token keeps its short TTL and its meaning — it stays the thing
-- that is inert fleet-wide (no roles) and unreachable by the denylist. Nothing
-- about it changes; it simply stops being the only thing standing between a
-- customer and another SMS.
--
-- WHAT IS STORED. The SHA-256 of the token, never the token. A refresh token is
-- 32 random bytes, so it has full entropy and a bare SHA-256 is the right
-- primitive here — the same call the fleet already makes for refresh/device/
-- denylist tokens, and NOT the keyed HMAC that low-entropy secrets (a 6-digit
-- OTP, a voucher code) require. A database read therefore yields nothing usable.
CREATE TABLE loyalty_refresh_tokens (
    id             UUID         PRIMARY KEY,

    -- SHA-256 of the presented token, lowercase hex (64 chars). UNIQUE because
    -- a lookup is by this column and a duplicate would be a hash collision or a
    -- generator fault — either way something to fail loudly on, not to resolve
    -- by picking a row.
    token_hash     CHAR(64)     NOT NULL UNIQUE,

    -- E.164 with the leading '+', the same spelling phone_registrations keys on
    -- and loyalty_users.phone_number stores. Every writer normalises through
    -- UserService.normalizePhone first, so there is one spelling and no drift.
    phone_number   VARCHAR(32)  NOT NULL,

    -- The FAMILY this token belongs to. Rotation issues a successor with the
    -- same chain_id, so one customer signing in on one device produces one
    -- chain however many times it rotates. It is what makes reuse detection
    -- actionable: on a replay we revoke the chain, which severs the attacker
    -- AND the legitimate device (both hold tokens from it), rather than
    -- guessing which of the two is the thief.
    chain_id       UUID         NOT NULL,

    -- How the phone was proved for the session that STARTED this chain — the
    -- access token's `services` scope marker (`loyalty-otp` from ticketing's
    -- OTP verify, or `loyalty-session` from this service's own issuer). Carried
    -- forward unchanged through every rotation, so a chain still names its
    -- origin after months of refreshes and an incident on one proof channel can
    -- be scoped to the chains it started.
    origin_scope   VARCHAR(40)  NOT NULL,

    issued_at      TIMESTAMPTZ  NOT NULL,

    -- Sliding, not absolute: each rotation issues a successor with a fresh
    -- window. An app in regular use therefore never re-proves, which is the
    -- point — one SMS per customer for life. An app untouched for the whole
    -- window has its chain age out and the customer proves the phone again,
    -- which is the bound that keeps an abandoned device from being a permanent
    -- credential.
    expires_at     TIMESTAMPTZ  NOT NULL,

    -- Stamped when this row is ROTATED. A used row is spent history, never a
    -- grant: presenting one again is the reuse signal described above. NULL
    -- means the row is the live tip of its chain.
    used_at        TIMESTAMPTZ,

    -- Tombstone, same reasoning as phone_registrations.revoked_at (V40) and the
    -- FX clear (V39): revocation is an event worth keeping, and "why was this
    -- customer signed out" is only answerable if the row survives to say so.
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(64),

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The lookup on every refresh: hash → row. UNIQUE above already indexes it;
-- named here only for the chain and phone queries.
CREATE INDEX idx_loyalty_refresh_chain ON loyalty_refresh_tokens (chain_id);

-- Operator revocation ("sign this customer out everywhere") and the reuse
-- sweep both start from the phone.
CREATE INDEX idx_loyalty_refresh_phone ON loyalty_refresh_tokens (phone_number);

-- NOTE for whoever adds retention. Rows are small and are the audit trail of
-- who held a session when, so nothing prunes them today. When that changes,
-- delete only rows that are BOTH long past expires_at and not the newest row of
-- their chain — deleting a chain's tip would make a legitimate refresh look
-- like an unknown token rather than an expired one, and the customer would be
-- told their session was rejected instead of that it had lapsed.
