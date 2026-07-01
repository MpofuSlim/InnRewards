-- Guard against double-reversal (points/money creation): a given original
-- transaction may be reversed AT MOST once.
--
-- Without this, two concurrent POST /loyalty/transactions/{id}/reverse calls
-- both read the original with status=POSTED, both insert a compensating
-- ADJUSTMENT row, and the wallet is credited TWICE for a single reversal —
-- inventing points out of nothing. TransactionService.reverse now also takes a
-- PESSIMISTIC_WRITE lock on the original row so concurrent reversals serialize
-- and the loser is rejected with ALREADY_REVERSED; this partial unique index is
-- the DB-level backstop that holds even if that application guard regresses.
--
-- Partial (WHERE reverses_id IS NOT NULL) because the overwhelming majority of
-- ledger rows are not reversals — only reversal rows carry a reverses_id — so we
-- keep the unique key narrow.
--
-- Assumes no pre-existing duplicate reverses_id rows. On a clean staging DB this
-- holds; if an earlier build already double-reversed a transaction, those
-- duplicates must be reconciled before this migration can apply.
CREATE UNIQUE INDEX uq_txn_reverses_id
    ON loyalty_transactions (reverses_id)
    WHERE reverses_id IS NOT NULL;
