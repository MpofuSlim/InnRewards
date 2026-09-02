-- Multi-currency, part 2 (earn path): freeze what each transaction was worth in
-- the BASE currency (USD) at the moment it was posted, and which FX rate row
-- said so.
--
-- WHY FREEZE. `amount` is what the customer actually transacted (ZWG 500), and
-- points are awarded on the USD value of that amount. ZWG moves fast, so
-- recomputing the USD value later — at a rate that did not exist when the
-- transaction happened — would silently restate history: the same row would be
-- worth a different number of dollars every day, and a points report run twice
-- would disagree with itself. So the converted value is computed ONCE, on the
-- write, and every later reader reads it back. Same rule the redemption
-- `amount` already follows: read back, never recompute.
--
-- EARN RATE IS NOW POINTS-PER-USD. `loyalty_rules.points_per_unit` is
-- re-interpreted as "points per 1 USD" rather than "points per 1 unit of
-- whatever currency the transaction happened to be in". Deliberately NO data
-- migration: every rule in existence was authored against USD transactions
-- (the pre-multi-currency cell was USD-only and the V36 guard refused anything
-- else), so the numbers already mean points-per-USD. The same applies to
-- `loyalty_rules.min_transaction_amount` — the earning floor is now compared
-- against the USD value, which is what it already effectively was.
ALTER TABLE loyalty_transactions
    -- The USD value of `amount` at the rate in force when this row was written.
    -- NUMERIC(19,4), matching `amount` and every other money column.
    --
    -- NULL is a real answer, not missing data: rows written before this
    -- migration were never converted (see the backfill below for the ones we
    -- CAN state honestly). A reader must treat NULL as "not known in USD",
    -- never as zero and never as "same as amount".
    ADD COLUMN base_amount NUMERIC(19,4),

    -- The exchange_rates row (V36) whose rate produced base_amount — the
    -- receipt for the conversion, so an auditor can see exactly which rate, set
    -- by whom and effective when, priced this transaction.
    --
    -- NULL means no conversion was needed or recorded: a USD transaction
    -- (identity — USD is never stored in exchange_rates by construction) or a
    -- pre-V37 row. No FK: rate history must outlive any row-level cleanup, and
    -- the ledger's other cross-table ids (rule_id, campaign_id, invoice_id) are
    -- plain UUIDs for the same reason.
    ADD COLUMN fx_rate_id UUID;

-- Backfill the rows we can state truthfully: a USD transaction's base value IS
-- its amount, by definition of the base currency. This keeps base_amount usable
-- as a single summable column across old and new rows for the whole USD history
-- (which, per the note above, is all of it on every cell shipped so far).
--
-- Deliberately scoped to currency = 'USD'. A non-USD legacy row (if any cell
-- ever wrote one before the V36 guard) is left NULL rather than guessed at:
-- we do not know what rate was in force then, and inventing one would fabricate
-- money history. fx_rate_id stays NULL for every backfilled row — no rate row
-- was consulted, and pointing at one that did not exist at the time would be a
-- lie in the audit trail.
UPDATE loyalty_transactions
   SET base_amount = amount
 WHERE currency = 'USD'
   AND amount IS NOT NULL
   AND base_amount IS NULL;
