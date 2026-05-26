-- Gap #18: delete the verify/reject document model.
--
-- The checklist status enum collapses from {PENDING, RECEIVED, VERIFIED,
-- REJECTED, NOT_REQUIRED} to {PENDING, SUBMITTED, NOT_REQUIRED}. RECEIVED and
-- VERIFIED both meant "the LSP submitted a file" — they collapse into
-- SUBMITTED. REJECTED meant "ops rejected the submission"; we keep the file
-- if one was uploaded (still SUBMITTED) and otherwise reset the row to
-- PENDING. The verify/reject reason columns are dropped — they have no
-- semantic home in the new model.
UPDATE loan_application_document_checklist
SET status = 'SUBMITTED'
WHERE status IN ('RECEIVED', 'VERIFIED');

UPDATE loan_application_document_checklist
SET status = CASE
        WHEN file_name IS NOT NULL OR storage_key IS NOT NULL THEN 'SUBMITTED'
        ELSE 'PENDING'
    END
WHERE status = 'REJECTED';

ALTER TABLE loan_application_document_checklist
    DROP COLUMN IF EXISTS review_reason,
    DROP COLUMN IF EXISTS rejection_reason;
