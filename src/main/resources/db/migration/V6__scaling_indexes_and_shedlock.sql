-- Scaling readiness migration.
--
-- 1. idx_users_phone — phone-only lookup used by GET /loyalty/users/me/wallet
--    (hot path on every customer app open) and the promote-on-registration
--    webhook. The existing (tenant_id, phone_number) composite doesn't help
--    queries that omit tenant_id, so we add a dedicated index.
CREATE INDEX IF NOT EXISTS idx_users_phone
    ON loyalty_users(phone_number);

-- 2. idx_users_pending_created_at — partial index sized only by the live
--    PENDING population, so the daily expiry sweeper is O(stale) rather than
--    O(all_users). Drops to zero rows scanned once everyone has registered.
CREATE INDEX IF NOT EXISTS idx_users_pending_created_at
    ON loyalty_users(created_at)
    WHERE status = 'PENDING';

-- 3. idx_voucher_user_status — paired index on the columns used by
--    findByAssignedUserIdAndStatusIn (active-vouchers-for-a-user). Today
--    assigned_user_id is indexed and status is indexed separately, so the
--    optimiser has to pick one and filter. A composite index serves both
--    /me/wallet aggregation and the customer's "my vouchers" screen.
CREATE INDEX IF NOT EXISTS idx_voucher_user_status
    ON vouchers(assigned_user_id, status);

-- 4. ShedLock table — used by net.javacrumbs.shedlock to leader-elect the
--    @Scheduled jobs across loyalty-service replicas. Without this every
--    pod runs every cron simultaneously: 3 pods = 3x DB load + a real
--    race for invoice generation. With it, exactly one pod holds the lock
--    for the duration of each cron run.
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
