-- A fifth registration proof: the customer's own InnBucks session (V42).
--
-- Sits on top of V41 (VEENGU_SESSION), which shipped first. That mode was built
-- against Veengu's Frontend API before the partner's own Postman collections
-- showed the app actually authenticates against the InnBucks Client Service
-- API. Both source values are kept: V41 is applied history and is never edited,
-- and an unused enum value costs nothing.
--
-- THE PROBLEM V40 LEFT OPEN. V40 gave loyalty a place to record "the owner of
-- this phone proved they hold it", and two ways to be told: a signed partner
-- assertion, or a shared key. Both need the middleware team to build something,
-- and neither had a delivery date. Meanwhile every customer of the mobile app
-- sat PENDING — earning and receiving, refused at every spend.
--
-- THE PROOF THAT ALREADY EXISTED. The app authenticates its customers against
-- the InnBucks Client Service API: POST /auth/client-service/user/login takes
-- the customer's own username + PIN block and returns a user token. That token
-- IS a possession proof — it just could not be read by us, because the API
-- exposes no "whose token is this" endpoint.
--
-- So we ask the question the other way round. The caller claims a phone; we
-- call an msisdn-SCOPED endpoint with the caller's user token and the CLAIMED
-- msisdn. The middleware binds a user token to its own msisdn, so:
--
--     it answers          -> the token's holder owns that number  -> register
--     it refuses          -> someone paired their own login with
--                            someone else's number                -> 401
--     no answer at all    -> prove nothing, register nothing       -> 503
--
-- WHY THIS IS NOT THE `/validate` TRAP. The pre-login endpoint
-- /auth/client-service/msisdn/{msisdn}/validate answers "00" for EVERY real
-- InnBucks customer and needs only the app's own credentials, so it proves the
-- number EXISTS, never that the caller holds it — registering on it would let
-- anyone name any customer's number and then spend their points, which is
-- precisely what PENDING exists to prevent. The distinction between the two
-- endpoints is the entire security model of this mode: one is authorized by
-- the APP, the other by the CUSTOMER.
--
-- Same table, same semantics, revocable by source exactly like the others
-- (WHERE source = 'INNBUCKS_SESSION' AND registered_at BETWEEN ...) should the
-- middleware's binding ever be found not to hold.
ALTER TABLE phone_registrations
    DROP CONSTRAINT chk_phone_registration_source;

ALTER TABLE phone_registrations
    ADD CONSTRAINT chk_phone_registration_source
        CHECK (source IN ('TICKETING_OTP', 'PARTNER_ASSERTION', 'PARTNER_KEY',
                          'VEENGU_SESSION', 'INNBUCKS_SESSION'));
