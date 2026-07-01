-- The shop "code" column (e.g. "AVONDALE") was metadata for receipts and
-- back-office search but no business logic ever read it — voucher /
-- transaction routing already happens by shop UUID. Dropping the unused
-- field rather than leaving a dead nullable column on the entity.
ALTER TABLE shops DROP COLUMN IF EXISTS code;
