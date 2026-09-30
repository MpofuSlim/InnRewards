-- Customer support (call centre) — /loyalty/support/**.
--
-- Three tables whose names, columns and widths are a SHARED CONTRACT with
-- marketplace-service, which builds the same surface in parallel: one console
-- reads both services, and an agent's activity is one story told in two
-- databases. Change a width or a name here only in lock-step with it.
--
-- Nothing here is tenant-scoped. Support is a PLATFORM function: an agent
-- looks a customer up by phone across every tenant (the wallet is global per
-- phone since V21, registration is per phone since V40), so a tenant column
-- would describe nothing.

-- 1. support_activity — who looked at / did what, and when. Append-only: the
--    application maps it @Immutable and its repository exposes no update or
--    delete. It is also what a support LOOKUP is: the CUSTOMER_LOOKUP row's id
--    IS the lookupId an agent's later calls carry, so a drill-down can never
--    run without a logged lookup behind it, and the phone never has to travel
--    in a URL.
--
--    detail is a small JSON object of ids, enums and amounts. NEVER free text
--    (a reason goes where the underlying action already keeps it, or into a
--    support_note) and NEVER a raw phone (a SEARCH that found nothing records
--    the masked form). subject_id DOES hold the full E.164 for subject_kind
--    PHONE — that is the key a supervisor's "who looked at this customer"
--    query needs — and every response masks it.
CREATE TABLE support_activity (
    id            UUID         PRIMARY KEY,
    agent_uuid    VARCHAR(64)  NOT NULL,
    agent_login   VARCHAR(255),
    action        VARCHAR(40)  NOT NULL,
    subject_kind  VARCHAR(20),
    subject_id    VARCHAR(80),
    detail        VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_support_activity_agent
    ON support_activity (agent_uuid, created_at DESC);
CREATE INDEX idx_support_activity_subject
    ON support_activity (subject_kind, subject_id, created_at DESC);
CREATE INDEX idx_support_activity_created
    ON support_activity (created_at DESC);

-- 2. support_note — internal notes about a customer. Append-only by design: no
--    edit, no delete. A support log that can be rewritten proves nothing.
CREATE TABLE support_note (
    id            UUID          PRIMARY KEY,
    subject_kind  VARCHAR(20)   NOT NULL,
    subject_id    VARCHAR(80)   NOT NULL,
    body          VARCHAR(2000) NOT NULL,
    agent_uuid    VARCHAR(64)   NOT NULL,
    agent_login   VARCHAR(255),
    created_at    TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_support_note_subject
    ON support_note (subject_kind, subject_id, created_at DESC);

-- 3. support_message — every support-initiated customer message and its
--    outcome. Written PENDING before the gateway call (that row claims the
--    rate-limit slot) and completed after it, so an attempt is counted even
--    when the process dies between the two.
--
--    body is exactly what was sent — and NULL for a secret-bearing kind
--    (VOUCHER_RESEND carries a redeemable voucher code, which must never be at
--    rest outside the vouchers table).
CREATE TABLE support_message (
    id                 UUID         PRIMARY KEY,
    subject_kind       VARCHAR(20),
    subject_id         VARCHAR(80),
    recipient_msisdn   VARCHAR(20)  NOT NULL,
    recipient_role     VARCHAR(30)  NOT NULL,
    kind               VARCHAR(30)  NOT NULL,
    channel_requested  VARCHAR(20)  NOT NULL,
    delivered_via      VARCHAR(20),
    outcome            VARCHAR(20)  NOT NULL,
    body               TEXT,
    failure_code       VARCHAR(60),
    agent_uuid         VARCHAR(64)  NOT NULL,
    agent_login        VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL,
    completed_at       TIMESTAMPTZ,
    CONSTRAINT chk_support_message_channel
        CHECK (channel_requested IN ('SMS', 'WHATSAPP', 'SMS_THEN_WHATSAPP')),
    CONSTRAINT chk_support_message_delivered_via
        CHECK (delivered_via IS NULL OR delivered_via IN ('SMS', 'WHATSAPP')),
    CONSTRAINT chk_support_message_outcome
        CHECK (outcome IN ('PENDING', 'SENT', 'FAILED')),
    -- A secret-bearing kind never stores its text. Enforced here rather than
    -- trusted to every future write path.
    CONSTRAINT chk_support_message_secret_body
        CHECK (kind <> 'VOUCHER_RESEND' OR body IS NULL)
);
CREATE INDEX idx_support_message_recipient
    ON support_message (recipient_msisdn, created_at DESC);
CREATE INDEX idx_support_message_agent
    ON support_message (agent_uuid, created_at DESC);
CREATE INDEX idx_support_message_subject
    ON support_message (subject_kind, subject_id, created_at DESC);
CREATE INDEX idx_support_message_created
    ON support_message (created_at DESC);

-- 4. Phone finders for the customer 360. A support lookup asks "every voucher
--    and voucher order this PHONE appears on, in any role", and until now
--    nothing in the service asked that: vouchers were found by holder ACCOUNT
--    (idx_voucher_user_status) and orders by reference. Without these, every
--    lookup is a sequential scan of both tables.
--
--    Partial (IS NOT NULL): the sender / transferred-from / order assignee and
--    sender columns are NULL on most rows, and an IN (...) predicate never
--    matches NULL, so the planner can use a partial index for every query that
--    reads them.
CREATE INDEX IF NOT EXISTS idx_voucher_assignee_phone
    ON vouchers (assignee_phone) WHERE assignee_phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_voucher_sender_phone
    ON vouchers (sender_phone) WHERE sender_phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_voucher_transferred_from_phone
    ON vouchers (transferred_from_phone) WHERE transferred_from_phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_vpo_payer_phone
    ON voucher_purchase_orders (payer_phone);
CREATE INDEX IF NOT EXISTS idx_vpo_assignee_phone
    ON voucher_purchase_orders (assignee_phone) WHERE assignee_phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_vpo_sender_phone
    ON voucher_purchase_orders (sender_phone) WHERE sender_phone IS NOT NULL;

-- 5. The ledger drill-down pages points_ledger for a phone's wallets newest
--    first. idx_ledger_wallet (V1) finds the rows but leaves a sort; this
--    serves the ORDER BY too.
CREATE INDEX IF NOT EXISTS idx_ledger_wallet_created
    ON points_ledger (wallet_id, created_at DESC);

-- Append-only is enforced HERE, not just by the entities being @Immutable and
-- the repositories having no delete: that makes a rewrite impossible to add
-- by accident, from any code path or any future release. The same triggers
-- guard marketplace-service's copies of these tables (shared contract).
-- TRUNCATE (a statement, not a row operation) is unaffected.
CREATE FUNCTION support_log_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_support_activity_append_only
    BEFORE UPDATE OR DELETE ON support_activity
    FOR EACH ROW EXECUTE FUNCTION support_log_is_append_only();
CREATE TRIGGER trg_support_note_append_only
    BEFORE UPDATE OR DELETE ON support_note
    FOR EACH ROW EXECUTE FUNCTION support_log_is_append_only();

-- A message is written PENDING before the gateway is called and completed
-- ONCE afterwards; the trigger makes that the only change a row can ever see.
-- Nothing is deleted, a completed row is final, and who sent what to whom
-- never changes. body may still change while PENDING: which channel carried
-- the message, and so which form of the text was sent, is known only after
-- the send.
CREATE FUNCTION support_message_is_final() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'support_message is append-only';
    END IF;
    IF OLD.outcome <> 'PENDING' THEN
        RAISE EXCEPTION 'support_message % is already %', OLD.id, OLD.outcome;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.subject_kind IS DISTINCT FROM OLD.subject_kind
        OR NEW.subject_id IS DISTINCT FROM OLD.subject_id
        OR NEW.recipient_msisdn IS DISTINCT FROM OLD.recipient_msisdn
        OR NEW.recipient_role IS DISTINCT FROM OLD.recipient_role
        OR NEW.kind IS DISTINCT FROM OLD.kind
        OR NEW.channel_requested IS DISTINCT FROM OLD.channel_requested
        OR NEW.agent_uuid IS DISTINCT FROM OLD.agent_uuid
        OR NEW.agent_login IS DISTINCT FROM OLD.agent_login
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'support_message % may only record its outcome', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_support_message_is_final
    BEFORE UPDATE OR DELETE ON support_message
    FOR EACH ROW EXECUTE FUNCTION support_message_is_final();
