-- Stop giving the platform away by default.
--
-- A merchant onboarded with no fee configuration at ANY level (no fees on its
-- own record, no merchant rule, no tenant standard) bills nothing, forever, and
-- nothing surfaced that. MerchantService now REFUSES to create such a merchant
-- unless the caller explicitly waives billing, and the waiver is recorded here
-- so "free on purpose" is distinguishable from "free by accident" — which is
-- the whole point of the audit that goes with it.
ALTER TABLE merchants
    ADD COLUMN fee_waived        BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN fee_waived_reason VARCHAR(200);

-- Existing rows keep fee_waived = FALSE. That is deliberate: every merchant
-- already onboarded for free shows up in the audit as unwaived, which is
-- exactly the backlog this is meant to expose. Waive the ones that are
-- genuinely free-of-charge and the list shrinks to the real mistakes.
COMMENT ON COLUMN merchants.fee_waived IS
    'TRUE when someone deliberately onboarded this merchant with no billing.';
COMMENT ON COLUMN merchants.fee_waived_reason IS
    'Why billing was waived. Required when fee_waived is TRUE.';
