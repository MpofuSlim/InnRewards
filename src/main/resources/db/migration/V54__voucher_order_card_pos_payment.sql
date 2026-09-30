-- A voucher purchase order can be paid on the TILL'S OWN card machine (V54).
--
-- A card swiped on the shop's terminal never passes through our systems, so,
-- like cash, the cashier's confirmation is the payment proof. It is recorded as
-- its own method, not as CASH, because it settles through a different channel:
-- counted as cash, every card sale would be a shortage at the drawer count and
-- an unexplained credit on the acquirer's settlement statement.
--
-- The approval code printed on the terminal slip is REQUIRED at confirmation:
-- it is the one fact that ties this order to a real card transaction, which is
-- what lets finance reconcile each voucher against the acquirer statement, and
-- it stops a "card" click with no swipe behind it. card_last4 is optional and
-- helps with a dispute. The card number itself is never stored.
--
-- cash_confirmed_by keeps its name but now records the staff member who
-- confirmed either off-system payment (cash or card machine).

ALTER TABLE voucher_purchase_orders DROP CONSTRAINT chk_vpo_paid_via;
ALTER TABLE voucher_purchase_orders ADD CONSTRAINT chk_vpo_paid_via
    CHECK (paid_via IS NULL OR paid_via IN ('GATEWAY', 'CASH', 'CARD_POS'));

ALTER TABLE voucher_purchase_orders ADD COLUMN card_approval_code VARCHAR(12);
ALTER TABLE voucher_purchase_orders ADD COLUMN card_last4 VARCHAR(4);
