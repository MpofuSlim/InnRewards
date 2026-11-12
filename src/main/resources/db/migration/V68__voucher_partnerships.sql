-- V68: a voucher can be redeemable at partner merchants (owner decision, 2026-11-12).
--
-- A tenant invites another tenant into a PARTNERSHIP; the other side accepts or
-- declines; either side can end it; each side chooses which of its own merchants
-- take part. A voucher a partner generates may then name, in redeemableAt, any
-- of those merchants (or a merchant of the issuer's own tenant). The list is
-- fixed on the voucher at issue: ending a partnership never strands a prepaid
-- voucher already sold. A SUPER_ADMIN suspension stops use at once.
--
-- Settlement and the redemption fee already follow the REDEEMING merchant
-- (voucher_redemptions.merchant_id), so a use at a partner pays and bills that
-- partner with no change to money code.

CREATE TABLE voucher_partnerships (
    id                   UUID          PRIMARY KEY,
    inviter_tenant_id    UUID          NOT NULL REFERENCES tenants(id),
    invitee_tenant_id    UUID          NOT NULL REFERENCES tenants(id),
    status               VARCHAR(16)   NOT NULL,
    -- Accepted by the staging switch LOYALTY_PARTNERSHIP_AUTO_ACCEPT, not by the other side.
    auto_accepted        BOOLEAN       NOT NULL DEFAULT FALSE,
    invited_by           VARCHAR(254)  NOT NULL,
    invited_on_behalf    BOOLEAN       NOT NULL DEFAULT FALSE,
    invited_at           TIMESTAMPTZ   NOT NULL,
    decided_by           VARCHAR(254),
    decided_on_behalf    BOOLEAN,
    decided_at           TIMESTAMPTZ,
    ended_by             VARCHAR(254),
    ended_on_behalf      BOOLEAN,
    ended_at             TIMESTAMPTZ,
    suspended_by         VARCHAR(254),
    suspended_at         TIMESTAMPTZ,
    suspension_reason    VARCHAR(500),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_voucher_partnership_status
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DECLINED', 'ENDED')),
    CONSTRAINT chk_voucher_partnership_two_tenants CHECK (inviter_tenant_id <> invitee_tenant_id)
);

-- One live partnership per pair of tenants, whichever side invited.
CREATE UNIQUE INDEX uq_voucher_partnership_live_pair ON voucher_partnerships
    (LEAST(inviter_tenant_id, invitee_tenant_id), GREATEST(inviter_tenant_id, invitee_tenant_id))
    WHERE status IN ('INVITED', 'ACTIVE', 'SUSPENDED');
CREATE INDEX idx_voucher_partnership_inviter ON voucher_partnerships (inviter_tenant_id);
CREATE INDEX idx_voucher_partnership_invitee ON voucher_partnerships (invitee_tenant_id);

-- The merchants each side puts into the partnership.
CREATE TABLE voucher_partnership_merchants (
    partnership_id  UUID          NOT NULL REFERENCES voucher_partnerships(id),
    merchant_id     UUID          NOT NULL REFERENCES merchants(id),
    tenant_id       UUID          NOT NULL REFERENCES tenants(id),
    added_by        VARCHAR(254)  NOT NULL,
    added_at        TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (partnership_id, merchant_id)
);
CREATE INDEX idx_voucher_partnership_merchants_merchant ON voucher_partnership_merchants (merchant_id);

-- Where a voucher can be used besides its own merchant, fixed at issue (comma-
-- separated merchant ids, the issuer never listed). NULL = the issuer only, the
-- shape of every voucher before V68.
ALTER TABLE vouchers ADD COLUMN redeemable_merchant_ids TEXT;
ALTER TABLE voucher_purchase_orders ADD COLUMN redeemable_merchant_ids TEXT;
