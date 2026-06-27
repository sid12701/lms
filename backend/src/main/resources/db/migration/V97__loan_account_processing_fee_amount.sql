-- ADR 0004: the processing fee is charged at disbursement (deducted from the
-- cash sent to the borrower; the recorded principal is unchanged). The fee that
-- was actually applied is persisted here when a loan account reaches DISBURSED.
--
-- Nullable + forward-only: legacy rows stay NULL and AdminReportingService falls
-- back to LoanFeeCalculator at read time, preserving historical MIS output. No backfill.
ALTER TABLE loan_account
    ADD COLUMN processing_fee_amount NUMERIC(19, 2);
