CREATE TABLE loan_application_intake_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_application_id UUID NOT NULL REFERENCES loan_application(id) ON DELETE CASCADE,
    actor_username VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(128),
    payload_json VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_application_intake_audit_created_at
    ON loan_application_intake_audit (loan_application_id, created_at DESC);
