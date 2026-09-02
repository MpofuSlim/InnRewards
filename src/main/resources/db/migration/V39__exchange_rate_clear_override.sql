-- Multi-currency, part 4: let a tenant go BACK to the platform ("bank") rate
-- after it has set its own override.
--
-- THE PROBLEM. exchange_rates is append-only and scope precedence is absolute:
-- a tenant row always beats a platform row for that tenant. So once a tenant
-- sets an override there is no way to stop overriding — only to keep setting
-- new overrides forever. Deleting the row is not an option (append-only is what
-- makes the history an audit trail), and neither is "write an override equal to
-- today's bank rate", which silently re-freezes at a stale number the moment the
-- bank rate next moves.
--
-- THE MODEL. A "clear" is a TOMBSTONE ROW: a tenant-scoped, effective-dated row
-- that carries no rate and means "from this instant, this tenant has no
-- override". Resolution finds it exactly the way it finds a rate — latest
-- effective_from <= T — and, seeing it is a tombstone, falls through to the
-- platform scope. That keeps every property the table was built on: nothing is
-- ever mutated or deleted, the change is attributable (created_by, note) and
-- effective-dated (a clear can be scheduled), and the history reads as the true
-- sequence of decisions: overrode, overrode again, went back to the bank rate.
ALTER TABLE exchange_rates
    -- TRUE = tombstone: this row revokes the scope's override rather than
    -- setting a rate. Only meaningful on tenant-scoped rows; a platform-scoped
    -- tombstone would mean "no bank rate", which is just NO_FX_RATE, so the
    -- service refuses to write one.
    ADD COLUMN cleared BOOLEAN NOT NULL DEFAULT FALSE;

-- A tombstone genuinely has NO rate, and saying so with NULL is honest — the
-- alternative (storing a placeholder number nobody is allowed to read) is the
-- kind of value that eventually gets read. So rate_per_usd becomes nullable,
-- and a CHECK enforces the real invariant instead: a row either sets a positive
-- rate or clears the override, never neither and never both.
--
-- The original inline CHECK (rate_per_usd > 0) from V36 is dropped by its
-- Postgres-generated name; IF EXISTS keeps this safe if it was ever named
-- differently.
ALTER TABLE exchange_rates
    ALTER COLUMN rate_per_usd DROP NOT NULL;

ALTER TABLE exchange_rates
    DROP CONSTRAINT IF EXISTS exchange_rates_rate_per_usd_check;

ALTER TABLE exchange_rates
    ADD CONSTRAINT chk_exchange_rate_sets_or_clears
    CHECK (
        (cleared = FALSE AND rate_per_usd IS NOT NULL AND rate_per_usd > 0)
        OR
        (cleared = TRUE  AND rate_per_usd IS NULL)
    );

-- Every existing row is a real rate, never a tombstone — the DEFAULT FALSE
-- above already states that for the rows in place, and no backfill is needed.
