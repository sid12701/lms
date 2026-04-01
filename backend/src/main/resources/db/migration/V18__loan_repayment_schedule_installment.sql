CREATE TABLE loan_repayment_schedule_installment (
    id UUID PRIMARY KEY,
    loan_account_id UUID NOT NULL REFERENCES loan_account (id),
    installment_number INTEGER NOT NULL,
    due_date DATE NOT NULL,
    opening_principal NUMERIC(19, 2) NOT NULL,
    principal_due NUMERIC(19, 2) NOT NULL,
    interest_due NUMERIC(19, 2) NOT NULL,
    installment_amount NUMERIC(19, 2) NOT NULL,
    closing_principal NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_loan_repayment_schedule_account_installment
    ON loan_repayment_schedule_installment (loan_account_id, installment_number);
