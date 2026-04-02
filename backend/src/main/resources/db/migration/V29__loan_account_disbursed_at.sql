ALTER TABLE loan_account
    ADD COLUMN disbursed_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_loan_account_disbursed_at ON loan_account (disbursed_at);
