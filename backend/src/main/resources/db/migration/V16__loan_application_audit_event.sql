CREATE TABLE loan_application_audit_event (
    id UUID PRIMARY KEY,
    loan_application_id UUID NOT NULL REFERENCES loan_application (id),
    action VARCHAR(64) NOT NULL,
    actor_username VARCHAR(255) NOT NULL,
    from_status VARCHAR(64) NOT NULL,
    to_status VARCHAR(64) NOT NULL,
    note VARCHAR(500) NOT NULL,
    reason_code VARCHAR(64),
    correlation_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_loan_application_audit_event_application_created_at
    ON loan_application_audit_event (loan_application_id, created_at DESC);
