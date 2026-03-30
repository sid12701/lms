ALTER TABLE loan_application
    ADD COLUMN assigned_to_username VARCHAR(128),
    ADD COLUMN assigned_by_username VARCHAR(128),
    ADD COLUMN assigned_at TIMESTAMPTZ;

CREATE TABLE loan_application_assignment_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_application_id UUID NOT NULL REFERENCES loan_application(id) ON DELETE CASCADE,
    from_assignee_username VARCHAR(128),
    to_assignee_username VARCHAR(128),
    actor_username VARCHAR(128) NOT NULL,
    note VARCHAR(500),
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_application_assignment_event_application_created_at
    ON loan_application_assignment_event (loan_application_id, created_at DESC);
