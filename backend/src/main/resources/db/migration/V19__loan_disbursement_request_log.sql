CREATE TABLE loan_disbursement_request_log (
    id UUID PRIMARY KEY,
    loan_account_id UUID NOT NULL REFERENCES loan_account (id),
    actor_username VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    provider_name VARCHAR(64) NOT NULL,
    provider_request_id VARCHAR(128) NOT NULL,
    provider_status VARCHAR(64) NOT NULL,
    request_payload_json TEXT NOT NULL,
    response_payload_json TEXT NOT NULL,
    correlation_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_loan_disbursement_request_log_account_created_at
    ON loan_disbursement_request_log (loan_account_id, created_at DESC);
