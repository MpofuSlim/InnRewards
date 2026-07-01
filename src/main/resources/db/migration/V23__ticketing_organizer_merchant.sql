-- V23: ticketing earn/redeem — an event organizer maps to one loyalty merchant,
-- all under a single seeded "Ticketing" tenant. Organizer merchants are lazily
-- find-or-created (keyed by organizer_uuid) on first earn/redeem.

ALTER TABLE merchants ADD COLUMN organizer_uuid UUID;

-- One merchant per organizer; partial so existing merchants (NULL) are unaffected
-- and the index backs the find-or-create race.
CREATE UNIQUE INDEX uk_merchant_organizer ON merchants (organizer_uuid)
    WHERE organizer_uuid IS NOT NULL;

-- Fixed id (mirrors TicketingLoyaltyService.TICKETING_TENANT_ID) so no tenant
-- lookup/create race is needed. Idempotent on re-run / fresh DB.
INSERT INTO tenants (id, code, name, status, created_at)
VALUES ('0a571c1c-7c75-4000-a000-000000000001', 'ticketing', 'Ticketing', 'ACTIVE', NOW())
ON CONFLICT (id) DO NOTHING;
