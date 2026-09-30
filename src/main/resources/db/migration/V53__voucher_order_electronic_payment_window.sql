-- How long an electronic payment (EcoCash prompt, InnBucks code, card
-- checkout) for this order may still complete. payment-service calls
-- extend-expiry before it mints every instrument, asking for the
-- instrument's lifetime plus a safety margin; loyalty records that window
-- here.
--
-- confirm-cash refuses while it is open. Otherwise a cashier could take
-- cash for an order whose customer is still looking at an EcoCash PIN
-- prompt or an InnBucks code: if the customer then approves it, the
-- customer has paid twice, and the late electronic payment is refused on
-- the already-paid order and becomes a manual refund.
--
-- NULL means no electronic attempt was ever started. That is the truth for
-- every existing row, so there is no backfill.
ALTER TABLE voucher_purchase_orders
    ADD COLUMN electronic_payment_until TIMESTAMPTZ;
