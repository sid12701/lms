ALTER TABLE loan_application_document_checklist
    ADD COLUMN file_name VARCHAR(255),
    ADD COLUMN file_reference VARCHAR(500),
    ADD COLUMN content_type VARCHAR(128),
    ADD COLUMN uploaded_at TIMESTAMPTZ,
    ADD COLUMN uploaded_by_username VARCHAR(128);
