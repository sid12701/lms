CREATE TABLE disbursement_outcome_audit (
    id UUID PRIMARY KEY,
    loan_application_id UUID NOT NULL REFERENCES loan_application (id),
    loan_account_id UUID NOT NULL REFERENCES loan_account (id),
    actor_username VARCHAR(255) NOT NULL,
    actor_ip VARCHAR(64),
    correlation_id VARCHAR(128),
    source VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    provider_request_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_disbursement_outcome_audit_loan_app_created
    ON disbursement_outcome_audit (loan_application_id, created_at DESC);

CREATE INDEX idx_disbursement_outcome_audit_actor_created
    ON disbursement_outcome_audit (actor_username, created_at DESC);

CREATE INDEX idx_disbursement_outcome_audit_source_created
    ON disbursement_outcome_audit (source, created_at DESC);

CREATE INDEX idx_disbursement_outcome_audit_correlation_id
    ON disbursement_outcome_audit (correlation_id);
