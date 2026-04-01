CREATE TABLE loan_account (
    id UUID PRIMARY KEY,
    loan_application_id UUID NOT NULL UNIQUE REFERENCES loan_application (id),
    borrower_id UUID NOT NULL REFERENCES borrower (id),
    lsp_id UUID NOT NULL REFERENCES lsp (id),
    loan_product_id UUID NOT NULL REFERENCES loan_product (id),
    account_number VARCHAR(64) NOT NULL UNIQUE,
    principal_amount NUMERIC(19, 2) NOT NULL,
    tenure_months INTEGER NOT NULL,
    status VARCHAR(64) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_loan_account_borrower ON loan_account (borrower_id);
CREATE INDEX idx_loan_account_lsp ON loan_account (lsp_id);
