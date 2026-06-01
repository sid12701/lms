CREATE TABLE borrower_bank_details_update_audit (
    id UUID PRIMARY KEY,
    borrower_id UUID NOT NULL REFERENCES borrower (id),
    lsp_id UUID REFERENCES lsp (id),
    actor_username VARCHAR(255) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    previous_bank_account_number VARCHAR(64),
    previous_bank_name VARCHAR(255),
    previous_ifsc_code VARCHAR(11),
    previous_account_holder_name VARCHAR(255),
    new_bank_account_number VARCHAR(64) NOT NULL,
    new_bank_name VARCHAR(255),
    new_ifsc_code VARCHAR(11) NOT NULL,
    new_account_holder_name VARCHAR(255),
    client_ip VARCHAR(64),
    correlation_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_borrower_bank_details_audit_borrower_created
    ON borrower_bank_details_update_audit (borrower_id, created_at DESC);

CREATE TABLE loan_disbursement_bank_mismatch_log (
    id UUID PRIMARY KEY,
    loan_application_id UUID NOT NULL REFERENCES loan_application (id),
    lsp_id UUID NOT NULL REFERENCES lsp (id),
    submitted_bank_account_number VARCHAR(64),
    submitted_ifsc_code VARCHAR(11),
    submitted_account_holder_name VARCHAR(255),
    correlation_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_disbursement_bank_mismatch_app_lsp_created
    ON loan_disbursement_bank_mismatch_log (loan_application_id, lsp_id, created_at DESC);
