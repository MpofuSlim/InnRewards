-- A sixth registration source: the InnBucks customer directory (V44).
--
-- THE DECISION THIS RECORDS. The platform owner has decided (2026-09) that
-- EVERY InnBucks customer is eligible to spend loyalty points. Under that rule
-- the question a registration answers changes for this source: not "did the
-- owner of this phone prove they hold it" but "is this phone a real InnBucks
-- customer". The proof is GET /auth/client-service/msisdn/{msisdn}/validate,
-- authorized by the APP's own credentials — the endpoint V42 documented as
-- unusable as an OWNERSHIP probe precisely because it answers "00" for every
-- real customer, whoever asks. That property is disqualifying for identity and
-- is exactly the point for eligibility.
--
-- WHAT KEEPS THIS BOUNDED. A row with this source promotes projections
-- (PENDING -> ACTIVE) like any other, but the registration endpoint NEVER
-- returns a loyalty session for it: selfServiceMode() in
-- PartnerRegistrationController excludes the mode, so nobody gains the ability
-- to ACT as the phone they named — they only cause its (already eligible)
-- owner to become spendable. Identity still comes from the OTP / assertion
-- channels. BLOCKED and operator-deactivated accounts stay untouched, as with
-- every source (registerPhone's rules are source-independent).
--
-- ITS OWN SOURCE VALUE, like the others, so the whole population is revocable
-- as a batch if the eligibility decision is ever reversed:
--
--   UPDATE phone_registrations
--      SET revoked_at = now(), revoked_reason = 'eligibility decision reversed'
--    WHERE source = 'INNBUCKS_VALIDATE' AND revoked_at IS NULL;
--
-- (then re-PENDING the projections it activated, per the V40 revocation notes).
ALTER TABLE phone_registrations
    DROP CONSTRAINT chk_phone_registration_source;

ALTER TABLE phone_registrations
    ADD CONSTRAINT chk_phone_registration_source
        CHECK (source IN ('TICKETING_OTP', 'PARTNER_ASSERTION', 'PARTNER_KEY',
                          'VEENGU_SESSION', 'INNBUCKS_SESSION', 'INNBUCKS_VALIDATE'));
