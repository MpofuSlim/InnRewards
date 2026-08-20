-- Earn-integrity phase 1 (attribution): every transaction records WHO created
-- it and through WHICH door the earn arrived.
--
-- posted_by: the staff/customer user-service UUID from the caller's JWT
--   (CallerDetails.currentUserId()). NULL for server-to-server flows (guest /
--   shop checkout, ticketing accrual) and for every row that predates this
--   migration — a NULL here means "unattributed legacy", never an error.
--   This history cannot be backfilled, which is why attribution ships before
--   any of the enforcement it enables.
--
-- channel: how an EARN was posted — TYPED_PHONE (staff keyed the recipient),
--   QR_PRESENCE (customer scanned, credits only the authenticated scanner),
--   CHECKOUT_S2S (server-side checkout/accrual). NULL for non-earn rows
--   (reversals, adjustments, transfers, redemptions) and legacy rows.
--   The typed-phone channel is the fraud surface the earn-integrity guards
--   key on; QR_PRESENCE is exempt from SELF_EARN by design (the scanner IS
--   the recipient).
ALTER TABLE loyalty_transactions ADD COLUMN posted_by UUID;
ALTER TABLE loyalty_transactions ADD COLUMN channel VARCHAR(20);

-- The concentration report (phase 2) groups by cashier; give it an index now
-- so the report lands on warmed data.
CREATE INDEX idx_txn_posted_by ON loyalty_transactions (posted_by);
