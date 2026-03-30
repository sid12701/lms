CREATE TABLE loan_application_status_transition (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_application_id UUID NOT NULL REFERENCES loan_application(id) ON DELETE CASCADE,
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    actor_username VARCHAR(255) NOT NULL,
    note VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_application_status_transition_application_created_at
    ON loan_application_status_transition (loan_application_id, created_at DESC);
