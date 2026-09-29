-- V17 pinned fraud_attempts.reason to the eleven FraudAttempt.Reason values
-- that existed then. Four were added to the enum later without widening the
-- CHECK: NOT_ASSIGNEE (voucher redeem by a customer who is not the holder),
-- SELF_EARN, STAFF_RECIPIENT and ADJUSTMENT_LIMIT (earn integrity).
--
-- Every one of those refusals records evidence through FraudService.record
-- before it throws, and that INSERT violated the CHECK. The violation escaped
-- as DataIntegrityViolationException, which GlobalExceptionHandler renders as
-- a 409, so the caller got a 409 instead of the documented refusal and no
-- evidence was kept. The voucher redeem lockout counts NOT_ASSIGNEE as a
-- guess, which only works once that refusal actually reaches the controller.
--
-- Widening only: every value the old constraint accepted is still accepted,
-- so no existing row can fail it and no data rewrite is needed.
ALTER TABLE fraud_attempts DROP CONSTRAINT IF EXISTS chk_fraud_attempts_reason;
ALTER TABLE fraud_attempts
    ADD CONSTRAINT chk_fraud_attempts_reason
    CHECK (reason IN ('INVALID_CODE','BAD_SIGNATURE','EXPIRED','ALREADY_REDEEMED','USAGE_EXCEEDED',
                      'WRONG_MERCHANT','NOT_ASSIGNEE','BLOCKED_DEVICE','BLOCKED_USER','QR_REUSED',
                      'QR_EXPIRED','QR_BAD_SIGNATURE','SELF_EARN','STAFF_RECIPIENT','ADJUSTMENT_LIMIT'));
