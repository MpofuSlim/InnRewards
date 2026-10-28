-- Customer support retention: support_activity and support_message rows may be
-- deleted once they are more than 12 months old, and never sooner.
--
-- V55 made both tables refuse every DELETE, so nothing could ever sweep them.
-- The retention schedule the support module proposes (for the data protection
-- officer to confirm) is 12 months for read logs, support activity and message
-- logs. The 12 months is enforced HERE, in the same triggers that keep the logs
-- append-only: no code path, the retention job's included, can delete a row
-- younger than that. A longer period is the job's setting
-- (loyalty.retention.support-months); a shorter one needs a migration.
--
-- support_note is NOT changed: notes are the customer's case history, not a
-- log, and the schedule does not name them. It stays append-only with no
-- delete at all.
--
-- UPDATE is unchanged on both tables. The same triggers guard
-- marketplace-service's copies of these tables (shared contract): mirror this
-- there.

CREATE OR REPLACE FUNCTION support_log_is_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE'
        AND TG_TABLE_NAME = 'support_activity'
        AND OLD.created_at < now() - interval '12 months' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION support_message_is_final() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.created_at < now() - interval '12 months' THEN
            RETURN OLD;
        END IF;
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
