-- C05 — a foreclosure settlement receipt is bound to the quote it settles.
--
-- The partial unique index is the hard single-execution guarantee: whatever races at the
-- service layer, one quote can back at most one settlement receipt. Ordinary installment
-- receipts keep a NULL link and are unaffected.
--
-- No backfill. Historical FORECLOSURE_SETTLEMENT rows carry no recorded quote reference, so
-- any link would be inferred from amount/date coincidence rather than evidence (ADR 0006
-- migration discipline; H09's "identify inconsistencies, never rewrite financial history").
-- Pre-existing settlements therefore stay NULL and remain reconciliation material.
ALTER TABLE loan_payment_transaction
    ADD COLUMN foreclosure_quote_id UUID REFERENCES loan_foreclosure_quote (id);

CREATE UNIQUE INDEX uk_loan_payment_transaction_foreclosure_quote
    ON loan_payment_transaction (foreclosure_quote_id)
    WHERE foreclosure_quote_id IS NOT NULL;
