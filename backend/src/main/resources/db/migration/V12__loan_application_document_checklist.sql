CREATE TABLE loan_application_document_checklist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_application_id UUID NOT NULL REFERENCES loan_application(id) ON DELETE CASCADE,
    document_type VARCHAR(64) NOT NULL,
    required BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    note VARCHAR(500),
    updated_by_username VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_loan_application_document_checklist_application_type UNIQUE (loan_application_id, document_type)
);

CREATE INDEX idx_loan_application_document_checklist_application_created_at
    ON loan_application_document_checklist (loan_application_id, created_at ASC);
