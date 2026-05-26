-- Gap #11 — auto-approval rule engine writes structured failure metadata onto
-- the REJECTED status transition row so downstream consumers (FE Blocking Issues
-- panel, audit explorer) can render the exact failed rules without parsing free
-- text out of the note column.

ALTER TABLE loan_application_status_transition
    ADD COLUMN rejection_reason_json TEXT;
