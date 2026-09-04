-- A fourth registration proof: the customer's own Veengu session (V41).
--
-- The app authenticates its customers against Veengu, and the middleware that
-- was to assert registrations to us (PARTNER_ASSERTION / PARTNER_KEY) has no
-- delivery date. The customer's live Veengu access token is proof we can check
-- OURSELVES: the FE forwards the token, loyalty calls Veengu's
-- GET /auth/identity with it, and the phone number comes from VEENGU'S answer
-- — never from the client. A forged request therefore proves nothing, which is
-- what makes this mode safe to expose to a mobile client when the shared-key
-- mode never was.
--
-- Same table, same semantics: a VEENGU_SESSION row is the same "this phone's
-- owner proved they hold it" fact as the other three sources, revocable by
-- source exactly like them (WHERE source = 'VEENGU_SESSION' AND registered_at
-- BETWEEN ...) if Veengu's session validation is ever found wanting.
ALTER TABLE phone_registrations
    DROP CONSTRAINT chk_phone_registration_source;

ALTER TABLE phone_registrations
    ADD CONSTRAINT chk_phone_registration_source
        CHECK (source IN ('TICKETING_OTP', 'PARTNER_ASSERTION', 'PARTNER_KEY', 'VEENGU_SESSION'));
