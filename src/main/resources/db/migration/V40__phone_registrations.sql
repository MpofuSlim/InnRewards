-- Registration is a property of the PHONE, not of a per-tenant projection.
--
-- THE PROBLEM. A LoyaltyUser is a per-tenant projection: six production paths
-- mint one whenever a phone is touched under a new tenant, always PENDING
-- (UserService.findOrCreatePending). The ONLY thing that ever flipped PENDING
-- to ACTIVE was promoteByPhone, called by ticketing's OTP signup webhook. So
-- loyalty's working definition of "this person proved they own this phone" was
-- "ticketing's user-service has a customer row for it" — and the customer app
-- now authenticates elsewhere and never walks that flow. Every one of its
-- customers is therefore permanently PENDING: they earn and receive, but every
-- spend path refuses them.
--
-- Two things were wrong, and this migration fixes the second so the first can
-- be fixed in code:
--
--  1. Nothing tells loyalty when a phone is proven. (Solved by the new
--     POST /loyalty/partner/registrations endpoint — off by default.)
--  2. Even when something did, the answer was recorded PER PROJECTION. A
--     customer promoted last year still gets a PENDING row the first time they
--     transact with a NEW merchant, because the fact lived in a status column
--     that only existing rows carried.
--
-- THE MODEL. `phone_registrations` holds the fact once, keyed by the E.164
-- phone — the same spelling MsisdnValidator.normalizeToE164 writes into
-- loyalty_users.phone_number and wallets.phone_number, so it joins to both with
-- no spelling drift. loyalty_users.status becomes a per-projection CACHE of it:
-- minted ACTIVE when the phone is registered, self-healed at the spend gate,
-- and never aged out by the sweeper while a registration stands.
CREATE TABLE phone_registrations (
    -- E.164 with the leading '+', exactly as loyalty_users.phone_number stores
    -- it. VARCHAR(32) matches that column's width.
    phone_number       VARCHAR(32)  PRIMARY KEY,

    -- When the proof was first accepted. Not the projection's created_at —
    -- a phone can accrue for months before anyone proves it.
    registered_at      TIMESTAMPTZ  NOT NULL,

    -- WHICH proof. Attribution matters because these sources have different
    -- trust stories and a compromise is revoked per source (see revoked_at):
    --   TICKETING_OTP     — ticketing user-service's signup webhook (the path
    --                       that has always existed).
    --   PARTNER_ASSERTION — a signed, phone-scoped assertion from the app's
    --                       backend (the middleware fronting Veengu auth).
    --   PARTNER_KEY       — the same endpoint in shared-key mode, for a caller
    --                       that cannot sign. Weaker: a leaked key can register
    --                       any phone, which is why it is not the default.
    source             VARCHAR(30)  NOT NULL,

    -- Opaque external identifier from the asserting party (e.g. the app's user
    -- id). No extra PII — it exists so an operator can trace a registration
    -- back to the account that caused it.
    source_ref         VARCHAR(120),

    -- Replay guard for the assertion mode. An assertion whose issued-at is not
    -- strictly newer than this is a replay: accepted with a 200 (the caller
    -- retried; that is not an error) but with no side effects.
    last_asserted_at   TIMESTAMPTZ,
    last_assertion_jti VARCHAR(64),

    -- Revocation is a TOMBSTONE, not a DELETE — same reasoning as the V39
    -- exchange-rate clear. If a partner key or signing key leaks, the response
    -- is to revoke the batch it minted (WHERE source = ... AND registered_at
    -- BETWEEN ...) and re-PENDING the projections it activated. Deleting the
    -- rows would destroy the very audit trail the incident needs.
    revoked_at         TIMESTAMPTZ,
    revoked_reason     VARCHAR(200),

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_phone_registration_source
        CHECK (source IN ('TICKETING_OTP', 'PARTNER_ASSERTION', 'PARTNER_KEY'))
);

-- Resolution asks exactly one question — "is there an unrevoked registration
-- for this phone?" — on the PENDING spend path and on every projection create.
-- Partial index so the lookup touches only live rows.
CREATE INDEX idx_phone_registration_live
    ON phone_registrations (phone_number)
    WHERE revoked_at IS NULL;

-- WHY there is NO backfill from loyalty_users.status.
--
-- The obvious backfill — "every phone with an ACTIVE projection has proven
-- itself, so register it" — cannot be justified from the data. V1 created
-- loyalty_users with `status ... DEFAULT 'ACTIVE'` and PENDING does not appear
-- until V6, so rows minted in that era are ACTIVE by database default, not by
-- any proof. Nothing in the table distinguishes them. Writing them into a table
-- whose entire meaning is "someone proved they own this number" would launder a
-- default into a fact, permanently and invisibly.
--
-- Existing ACTIVE rows keep working untouched: requireSpendable's ACTIVE branch
-- is unchanged, so nobody who could spend before this migration is refused
-- after it. What they do NOT get is the per-projection fix, until their phone is
-- registered by a real proof (the ticketing webhook, or the partner endpoint).
--
-- If you decide that population is worth registering anyway, it is a deliberate
-- data decision with its own change window, not a migration:
--
--   INSERT INTO phone_registrations (phone_number, registered_at, source)
--   SELECT phone_number, MIN(created_at), 'TICKETING_OTP'
--     FROM loyalty_users WHERE status = 'ACTIVE'
--    GROUP BY phone_number
--   ON CONFLICT (phone_number) DO NOTHING;
--
-- Run it only after counting what it would insert, and know that it attributes
-- to TICKETING_OTP a set that provably contains some rows no OTP ever touched.

-- Why an aged-out row is distinguishable from a deactivated one.
--
-- PendingUserExpirySweeper flips PENDING -> INACTIVE after loyalty.pending.ttl-days
-- (90), and promoteByPhone deliberately skips INACTIVE ("registration doesn't
-- unblock fraud holds"). That is right for a fraud hold and wrong for an age-out:
-- an app customer who accrued, waited 90 days for a registration signal that was
-- never built, and got aged out is exactly who we now want to recover. Without a
-- reason column the two are the same value and recovery cannot tell them apart.
ALTER TABLE loyalty_users
    ADD COLUMN status_reason VARCHAR(30);

ALTER TABLE loyalty_users
    ADD CONSTRAINT chk_loyalty_user_status_reason
    CHECK (status_reason IS NULL OR status_reason IN ('PENDING_EXPIRED', 'OPERATOR'));

-- Stamp the existing INACTIVE rows as age-outs so registerPhone can recover
-- them. ASSUMPTION, stated plainly: the sweeper is the only writer of INACTIVE
-- in the codebase — UserService.deactivate has no callers in src/main and
-- FraudService writes only BLOCKED — so every INACTIVE row on a cell today came
-- from the sweeper, unless someone ran UPDATE by hand. If you know of a manual
-- deactivation, clear its status_reason before deploying, or that account
-- becomes recoverable by registration.
UPDATE loyalty_users SET status_reason = 'PENDING_EXPIRED' WHERE status = 'INACTIVE';
