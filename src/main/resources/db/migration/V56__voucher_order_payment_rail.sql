-- The electronic rail a voucher purchase order was paid on (V56).
--
-- For an electronic payment loyalty only ever recorded paid_via = 'GATEWAY':
-- the rail (InnBucks 2D code, EcoCash, ZimSwitch online card) stayed in
-- payment-service, so the voucher report could not filter by payment type.
-- payment-service now sends it on confirm-payment as paymentRail.
--
-- Deliberately NO CHECK constraint: the value is payment-service's PaymentRail
-- name, and a rail it adds later must not make loyalty refuse a confirmation
-- the customer has already paid for (the same reason notification types are a
-- VARCHAR, not an enum). Null on every pre-V56 row and on CASH / CARD_POS.
ALTER TABLE voucher_purchase_orders ADD COLUMN payment_rail VARCHAR(32);

-- The voucher report now joins vouchers to their purchase order (payment type
-- column + filter); voucher_id had no index.
CREATE INDEX IF NOT EXISTS idx_vpo_voucher_id ON voucher_purchase_orders (voucher_id);
