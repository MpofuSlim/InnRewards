-- The redemption formula (business-model point 4): the points -> currency
-- conversion applied when a customer spends points. This is the ONE knob the
-- InnBucks super app (SUPER_ADMIN) sets, and it is deliberately PLATFORM-WIDE
-- with no tenant_id / merchant_id column: a merchant sets how points are EARNED
-- (loyalty_rules.points_per_unit), but the platform — which carries the
-- liability for every outstanding point — decides what a point is WORTH when
-- redeemed. A merchant must never be able to influence redemption value, so the
-- schema gives them nowhere to write it.
--
-- Append-only and EFFECTIVE-DATED, mirroring the ledger's own ethos: a rate is
-- never edited or deleted, a new row supersedes it. The rate in force at any
-- instant T is the row with the greatest effective_from <= T (ties broken by
-- created_at). That gives a full audit trail — who changed the rate, when it
-- took effect, and what a redemption was valued at on any past date — for free.
CREATE TABLE redemption_rates (
    id             UUID PRIMARY KEY,

    -- Points required to redeem ONE unit of currency. 100 => 100 points buys
    -- $1 of value; a $2.50 discount costs 250 points. Same orientation as the
    -- legacy loyalty.points.redeem-rate env var this table replaces, so the
    -- seeded default below is behaviour-preserving. Must be strictly positive
    -- (a zero/negative rate would make points free or sign-flip value); the
    -- CHECK is the last line of defence behind the service-layer validation.
    points_per_unit NUMERIC(19,4) NOT NULL CHECK (points_per_unit > 0),

    -- ISO 4217. The cell is USD; the column lets a future multi-currency cell
    -- hold one rate per currency without a schema change. Resolution is always
    -- scoped by currency so USD and (say) ZWG never cross.
    currency       VARCHAR(8) NOT NULL DEFAULT 'USD',

    -- When this rate STARTS being the one in force. Not created_at: an operator
    -- can schedule a rate change ahead of time (effective_from in the future)
    -- and the resolver will not pick it up until then.
    effective_from TIMESTAMPTZ NOT NULL,

    -- Who set it (JWT userId). NULL only for the seeded bootstrap row below,
    -- which predates any operator action.
    created_by     UUID,

    -- Optional operator note ("Q3 promo devaluation", "launch rate") — the WHY
    -- behind a rate change, surfaced in the history endpoint.
    note           VARCHAR(500),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The hot path: "the rate in force for <currency> at <instant>" =
-- ORDER BY effective_from DESC, created_at DESC LIMIT 1 over rows with
-- effective_from <= :at. This index serves exactly that scan.
CREATE INDEX idx_redemption_rate_lookup
    ON redemption_rates (currency, effective_from DESC, created_at DESC);

-- Seed the platform default so the resolver ALWAYS finds a rate — there is no
-- window where a redemption has no formula to apply. Value (100) and currency
-- (USD) match the legacy env default (loyalty.points.redeem-rate:100), so
-- turning this table on changes no existing behaviour. effective_from is the
-- epoch so it governs every historical redemption too; created_by is NULL to
-- mark it as the system bootstrap rather than an operator decision.
INSERT INTO redemption_rates (id, points_per_unit, currency, effective_from, created_by, note)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    100.0000,
    'USD',
    TIMESTAMP WITH TIME ZONE 'epoch',
    NULL,
    'Seeded platform default (was loyalty.points.redeem-rate=100)'
);
