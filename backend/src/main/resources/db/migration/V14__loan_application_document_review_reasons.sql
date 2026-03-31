ALTER TABLE loan_application_document_checklist
    ADD COLUMN review_reason VARCHAR(500),
    ADD COLUMN rejection_reason VARCHAR(500);
