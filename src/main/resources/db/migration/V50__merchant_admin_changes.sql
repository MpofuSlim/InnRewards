-- Append-only history of merchants.admin_email.
--
-- admin_email decides who a merchant BELONGS to, in three places at once:
-- which account's sign-in resolves to it (user-service mints the merchantId
-- claim from it), who may manage it here (MerchantAuthz), and who receives
-- its invoices and paid-order notifications. Until now it was written once,
-- from the creator's JWT, and never again, so it needed no history.
--
-- It can now be named on someone's behalf at onboarding and reassigned or
-- cleared afterwards by a SUPER_ADMIN. Each of those moves authority over a
-- merchant from one person to another, so each leaves a row here. The
-- merchant row's updated_by is not enough: the next unrelated edit (an
-- activate, a fee change) overwrites it.
--
-- No foreign key to merchants, deliberately: an audit row must outlive its
-- subject, and a cascade would erase exactly the history someone would go
-- looking for after a merchant was removed.
CREATE TABLE merchant_admin_changes (
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    merchant_id    UUID         NOT NULL,
    change_type    VARCHAR(16)  NOT NULL,
    previous_email VARCHAR(255),
    new_email      VARCHAR(255),
    changed_by     VARCHAR(255),
    changed_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_merchant_admin_change_type
        CHECK (change_type IN ('CREATED', 'REASSIGNED', 'UNBOUND'))
);

CREATE INDEX idx_merchant_admin_changes_merchant
    ON merchant_admin_changes (merchant_id, changed_at);
