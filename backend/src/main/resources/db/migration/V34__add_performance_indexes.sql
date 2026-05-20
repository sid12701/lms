CREATE INDEX IF NOT EXISTS idx_loan_application_status ON loan_application (status);
CREATE INDEX IF NOT EXISTS idx_loan_application_lsp_id ON loan_application (lsp_id);
CREATE INDEX IF NOT EXISTS idx_loan_application_loan_product_id ON loan_application (loan_product_id);
CREATE INDEX IF NOT EXISTS idx_loan_application_created_at ON loan_application (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_loan_account_loan_application_id ON loan_account (loan_application_id);
CREATE INDEX IF NOT EXISTS idx_loan_account_disbursed_at ON loan_account (disbursed_at) WHERE disbursed_at IS NOT NULL;
