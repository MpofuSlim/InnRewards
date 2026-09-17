-- Voucher sender identity (V46).
--
-- A gifted voucher should read as coming from a PERSON, not from the platform:
-- "Tawanda Mpofu sent you a voucher" beats "your voucher is ready". The issue
-- request may now carry the sender's display name and phone; both are stamped
-- onto the voucher so the recipient's message, the wallet view and the sender's
-- own WhatsApp confirmation all agree on who gave what to whom.
--
-- These are PRESENTATION facts, distinct from the issuer_* audit columns:
-- issuer_* records who performed the API call (from the JWT, never the body),
-- sender_* records who the gift is FROM as it should be shown — a staff member
-- issuing on a customer's behalf makes the two differ legitimately.
--
-- Lengths mirror the existing assignee_name (200) / assignee_phone (32).
ALTER TABLE vouchers ADD COLUMN sender_name VARCHAR(200);
ALTER TABLE vouchers ADD COLUMN sender_phone VARCHAR(32);
