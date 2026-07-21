-- Pre-expiry warning markers for the daily ExpiryWarningSweeper: each live
-- point lot / unredeemed voucher entering the warning window gets ONE
-- "expires soon" notification; the marker is stamped on the attempt so the
-- sweep never re-warns and warned rows drop out of the scan.

ALTER TABLE point_lot ADD COLUMN expiry_warned_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_point_lot_expiry_warn
    ON point_lot (expires_at)
    WHERE remaining_amount > 0 AND expiry_warned_at IS NULL;

ALTER TABLE vouchers ADD COLUMN expiry_warned_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_vouchers_expiry_warn
    ON vouchers (expires_at)
    WHERE expires_at IS NOT NULL AND expiry_warned_at IS NULL;
