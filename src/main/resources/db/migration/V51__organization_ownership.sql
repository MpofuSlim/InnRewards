-- V51: merchants and tenants belong to an ORGANIZATION (user-service V39).
--
-- A business used to own its loyalty merchants through an email:
-- merchants.admin_email had to equal the caller's login email. That made one
-- email column the ownership key, the authorization rule AND the notification
-- address, for loyalty and — through user-service's claim lookup — for the
-- marketplace too. Ownership is now the organization the caller's session acts
-- for (the JWT orgId claim, honoured only for an OWNER or ADMIN of an
-- organization holding the loyalty product).
--
-- Both columns are NULLABLE and there is no backfill here: this database does
-- not hold the users or organizations, so it cannot map an email to one. A
-- merchant or tenant with no organization is reachable only by SUPER_ADMIN
-- until an operator stamps it (the staging remap script does exactly that from
-- admin_email / owner_email). Nothing is live in production, so nothing is
-- orphaned there.
--
-- admin_email is left in place, DORMANT: no entity maps it any more. Dropping
-- it would destroy the one record of who each pre-V51 merchant belonged to,
-- which the remap needs. Drop it in a later migration once that history is
-- worthless. merchant_admin_changes (V50) stays too, as applied history.

ALTER TABLE merchants ADD COLUMN organization_id UUID;
CREATE INDEX idx_merchant_organization ON merchants(organization_id);

-- A tenant (a loyalty PROGRAM) is created by one business. Its organization's
-- OWNERs and ADMINs are members of it without a tenant_members row, so a
-- colleague added through the organization can work in the program.
ALTER TABLE tenants ADD COLUMN organization_id UUID;
CREATE INDEX idx_tenant_organization ON tenants(organization_id);
