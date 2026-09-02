-- Multi-currency substrate (design: USD base + ZAR + ZWG): the platform FX
-- table that converts between the BASE currency (USD) and every other supported
-- currency. Points stay a single currency-neutral pool; their value is anchored
-- to USD, and any non-USD money amount crosses this table exactly once on its
-- way into or out of the points math (earn: local -> USD -> points; redeem:
-- points -> USD -> local). One anchor means a point is worth the same real
-- value in every currency — no cross-currency arbitrage lever.
--
-- Modelled on redemption_rates (V35): APPEND-ONLY and EFFECTIVE-DATED. A rate
-- is never edited or deleted; a new row supersedes it. Essential for ZWG,
-- which moves fast: the history IS the audit trail of every rate a
-- transaction was ever valued at.
--
-- TWO SCOPES, mirroring the loyalty_rules global-standard/merchant-override
-- inheritance: a PLATFORM row (tenant_id IS NULL) is the default every tenant
-- inherits — the "bank rate", entered by SUPER_ADMIN today and by the
-- scheduled feed job in a later phase — while a TENANT row (tenant_id set) is
-- that tenant's own override. Resolution precedence for (tenant, currency, T):
--
--   1. tenant override  (tenant_id = :tenant)                — latest in force
--   2. platform ADMIN   (tenant_id IS NULL, source = ADMIN)  — latest in force
--   3. platform FEED    (tenant_id IS NULL, source = FEED)   — latest in force
--
-- i.e. the bank rate applies ONLY when nobody set one: a tenant-set rate
-- overrides the bank rate for that tenant, and a platform-admin-set rate
-- overrides the automated feed for everyone. "Latest in force" = greatest
-- effective_from <= T, ties broken by created_at.
--
-- DELIBERATELY NO SEED. USD is the base (identity, never a row here), and any
-- other currency FAILS CLOSED until an operator sets its first rate
-- (NO_FX_RATE) — a missing rate must refuse, never default to 1.0.
CREATE TABLE exchange_rates (
    id             UUID PRIMARY KEY,

    -- NULL = a PLATFORM row (the inherited "bank rate" default); set = that
    -- tenant's own override, which beats every platform row for that tenant.
    -- No FK to tenants: rate history must survive a tenant row's lifecycle,
    -- same reason the ledger's tenant_id columns are plain UUIDs.
    tenant_id      UUID,

    -- The QUOTE currency (ISO 4217): units of this currency per 1 USD. The base
    -- currency itself is never stored — USD/USD is identity by construction,
    -- and the service refuses to write it (FX_BASE_IMMUTABLE).
    currency       VARCHAR(8) NOT NULL,

    -- Units of `currency` per 1 USD. NUMERIC(19,6) — two more decimals than the
    -- money columns' (19,4) because an FX rate is a multiplier, not an amount,
    -- and ZWG-style rates need the precision headroom. Strictly positive; the
    -- CHECK is the last line of defence behind the service-layer validation
    -- (which also enforces a sanity band against fat-fingered rates).
    rate_per_usd   NUMERIC(19,6) NOT NULL CHECK (rate_per_usd > 0),

    -- When this rate STARTS being the one in force. Not created_at: an operator
    -- can schedule a change ahead of time (effective_from in the future) and
    -- the resolver will not pick it up until then.
    effective_from TIMESTAMPTZ NOT NULL,

    -- Who wrote the row: ADMIN (an operator, e.g. the daily RBZ figure for ZWG)
    -- or FEED (the scheduled public-feed job, ZAR — a later phase). Kept on the
    -- row so the history distinguishes a human decision from an automated one.
    source         VARCHAR(16) NOT NULL,

    -- JWT userId of the operator for ADMIN rows; NULL for FEED rows.
    created_by     UUID,

    -- Optional note — the WHY ("RBZ interbank 2026-09-02"), surfaced in the
    -- history endpoint. MANDATORY at the service layer when an operator forces
    -- a rate through the sanity band.
    note           VARCHAR(500),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The hot path: "the rate in force for <currency> at <instant>" per scope =
-- ORDER BY effective_from DESC, created_at DESC LIMIT 1 over rows with
-- effective_from <= :at, filtered by tenant_id (= :tenant, or IS NULL for the
-- platform scopes). tenant_id sits second so both scope filters ride the same
-- index. Same shape as idx_redemption_rate_lookup otherwise.
CREATE INDEX idx_exchange_rate_lookup
    ON exchange_rates (currency, tenant_id, effective_from DESC, created_at DESC);
