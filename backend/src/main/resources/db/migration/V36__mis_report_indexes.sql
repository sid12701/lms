-- Speeds up the EXISTS subquery used by the MIS summary's portfolio-at-risk aggregation.
-- Partial index narrows the scan to installments that can contribute to DPD calculations.
CREATE INDEX IF NOT EXISTS idx_installment_overdue_lookup
    ON loan_repayment_schedule_installment (loan_account_id, due_date)
    WHERE outstanding_amount > 0;

-- Composite index matching the most common MIS filter combination (LSP + disbursal date range).
CREATE INDEX IF NOT EXISTS idx_loan_account_lsp_disbursed_at
    ON loan_account (lsp_id, disbursed_at);
