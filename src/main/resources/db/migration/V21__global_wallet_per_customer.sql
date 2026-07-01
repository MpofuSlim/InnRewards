-- V21: Globalize the points wallet — ONE MAIN wallet per CUSTOMER (phone).
--
-- Points earned at any tenant now land in the customer's single global MAIN
-- wallet and are spendable on anything in the app ("one balance" super-app
-- model). This is a backend-only change: no API/DTO/enum changes, so the FE
-- contract is untouched (Wallet.Type, WalletResponse, SubWalletRequest all
-- remain). Earn no longer routes to pockets; any pre-existing pocket balances
-- are folded into MAIN here so no points are stranded.
--
-- Per-tenant / per-merchant REPORTING is preserved: it derives from the
-- loyalty_transactions ledger (full tenant + merchant attribution on every
-- row), not from wallet balances.
--
-- NOTE: the consolidation runs on existing rows. On a fresh DB (incl. the test
-- container) the wallets table is empty, so it is all no-ops; the schema
-- changes are what matter there. It could not be exercised by the unit/IT
-- suite (Flyway runs before any test data is inserted), so it has been written
-- defensively and reviewed by hand. It assumes every wallet had a resolvable
-- owner (no orphans) and every customer with activity has a MAIN wallet (true:
-- a MAIN is created on first enrolment).

-- 1) New global resolution key, backfilled from the owning LoyaltyUser.
ALTER TABLE wallets ADD COLUMN phone_number VARCHAR(32);

UPDATE wallets w
SET phone_number = u.phone_number
FROM loyalty_users u
WHERE w.user_id = u.id;

-- 2) Decouple the wallet from the per-tenant projection. The wallet belongs to
-- the customer (phone) and is shared across their tenant projections, so the
-- ON DELETE CASCADE from loyalty_users must go (deleting one projection must
-- not delete the shared wallet). The legacy owner columns become nullable,
-- informational-only.
ALTER TABLE wallets DROP CONSTRAINT IF EXISTS wallets_user_id_fkey;
ALTER TABLE wallets ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE wallets ALTER COLUMN tenant_id DROP NOT NULL;

-- 3) Collapse every wallet a customer has (per-tenant MAIN duplicates AND any
-- pockets) into ONE canonical MAIN wallet per phone. Fold all balances into it,
-- repoint the ledger, then delete the rest. The ledger is repointed BEFORE the
-- delete so the points_ledger -> wallets ON DELETE CASCADE doesn't take history
-- with it. Canonical = the earliest MAIN wallet for the phone.

-- 3a) canonical.balance := sum of ALL the phone's wallet balances.
WITH canonical AS (
    SELECT DISTINCT ON (phone_number) id AS canonical_id, phone_number
    FROM wallets
    WHERE phone_number IS NOT NULL AND type = 'MAIN'
    ORDER BY phone_number, created_at, id)
UPDATE wallets c
SET balance = agg.total
FROM (
    SELECT cn.canonical_id, COALESCE(SUM(w.balance), 0) AS total
    FROM canonical cn
    JOIN wallets w ON w.phone_number = cn.phone_number
    GROUP BY cn.canonical_id) agg
WHERE c.id = agg.canonical_id;

-- 3b) repoint all of the phone's ledger entries to the canonical MAIN.
WITH canonical AS (
    SELECT DISTINCT ON (phone_number) id AS canonical_id, phone_number
    FROM wallets
    WHERE phone_number IS NOT NULL AND type = 'MAIN'
    ORDER BY phone_number, created_at, id)
UPDATE points_ledger pl
SET wallet_id = cn.canonical_id
FROM wallets w
JOIN canonical cn ON w.phone_number = cn.phone_number
WHERE pl.wallet_id = w.id AND w.id <> cn.canonical_id;

-- 3c) delete the now-merged wallets (other MAIN duplicates + all pockets).
WITH canonical AS (
    SELECT DISTINCT ON (phone_number) id AS canonical_id, phone_number
    FROM wallets
    WHERE phone_number IS NOT NULL AND type = 'MAIN'
    ORDER BY phone_number, created_at, id)
DELETE FROM wallets w
USING canonical cn
WHERE w.phone_number = cn.phone_number AND w.id <> cn.canonical_id;

-- 4) Enforce one wallet per (phone, type, pocket). After 3c only the MAIN
-- remains, so this guarantees exactly one MAIN per customer; it still permits
-- explicitly-created pockets later (the SubWalletRequest capability is kept),
-- but earn always credits MAIN. COALESCE keeps null-pocket MAIN rows unique.
CREATE UNIQUE INDEX uk_wallet_phone_type_pocket
    ON wallets (phone_number, type, COALESCE(pocket, ''));

CREATE INDEX idx_wallet_phone ON wallets (phone_number);

-- 5) Lock in the new key.
ALTER TABLE wallets ALTER COLUMN phone_number SET NOT NULL;
