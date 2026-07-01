-- vouchers.template_id is a foreign key (REFERENCES voucher_templates) but
-- was never indexed. Two costs: deleting/deactivating a template seq-scans
-- vouchers to check the FK, and "all vouchers issued from template X"
-- (reporting / template usage stats) does a full scan. Add the index.

CREATE INDEX IF NOT EXISTS idx_voucher_template
    ON vouchers (template_id);
