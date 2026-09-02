-- Multi-currency, part 3 (vouchers): freeze what an issued voucher is worth in
-- the BASE currency (USD) at the moment it was ISSUED, and which FX rate row
-- said so.
--
-- WHY ISSUE-TIME, NOT REDEEM-TIME. An outstanding voucher is a LIABILITY: the
-- platform has promised a discount it has not yet paid out. That promise is
-- made when the voucher is handed to the customer, so its cost is fixed then.
-- Revaluing the outstanding-voucher book at today's rate would make the
-- liability swing every day on currency movement alone, with no voucher issued
-- and none redeemed — the balance sheet would move because ZWG moved. Freezing
-- at issue is what makes "what do we owe on vouchers" a stable, auditable
-- number. Same read-back-never-recompute rule as loyalty_transactions (V37).
--
-- The voucher's `face_value` + `currency` are already an issue-time snapshot
-- (VoucherService copies them from the template so a later template edit cannot
-- retroactively change vouchers already in customers' hands). These columns
-- extend that same snapshot to the USD figure.
ALTER TABLE vouchers
    -- The USD value of `face_value` at the rate in force when the voucher was
    -- issued. NUMERIC(19,4), matching face_value and every other money column.
    --
    -- NULL is a real answer, not missing data, and it has THREE distinct causes
    -- a reader must not conflate:
    --   1. The voucher is not denominated in money at all — a PERCENT ("10% off"),
    --      FREE_ITEM or COMBO voucher. There is no amount to convert: 10 percent
    --      is not 10 of anything, and running it through an exchange rate would
    --      produce a confident, meaningless number. These are deliberately left
    --      NULL forever.
    --   2. The voucher predates this migration (see the backfill below for the
    --      ones we can state honestly).
    --   3. A pre-migration non-USD voucher, whose issue-time rate we do not know.
    -- In every case: NULL means "no USD liability figure", never zero.
    ADD COLUMN base_value NUMERIC(19,4),

    -- The exchange_rates row (V36) whose rate produced base_value — the receipt
    -- for the conversion. NULL when no conversion was needed or recorded: a USD
    -- voucher (identity), a non-money value type, or a pre-V38 row. No FK, same
    -- reasoning as loyalty_transactions.fx_rate_id.
    ADD COLUMN fx_rate_id UUID;

-- Backfill only what is true by definition: a USD voucher's base value IS its
-- face value. Scoped to AMOUNT vouchers because that is the only value type
-- whose number is money — a PERCENT voucher's "10" must stay NULL, not become
-- "10 USD" of liability, which is exactly the kind of confident-but-wrong figure
-- a liability report would then sum.
--
-- Non-USD legacy vouchers are left NULL rather than guessed at: we do not know
-- what rate was in force at their issuance, and inventing one would fabricate
-- liability history. fx_rate_id stays NULL for backfilled rows — no rate row was
-- consulted, and pointing at one that did not exist then would be a lie.
UPDATE vouchers
   SET base_value = face_value
 WHERE currency = 'USD'
   AND value_type = 'AMOUNT'
   AND face_value IS NOT NULL
   AND base_value IS NULL;
