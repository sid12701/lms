-- Gap #11 — rename PAYMENT_REINITIATION → DISBURSEMENT_RETRY across loan_application,
-- loan_application_status_transition and loan_application_audit_event.
-- FORECLOSED is added to the Java enum but no live transitions exist yet (reserved
-- for Phase 8), so there are no historical rows to backfill for it.

UPDATE loan_application
SET status = 'DISBURSEMENT_RETRY'
WHERE status = 'PAYMENT_REINITIATION';

UPDATE loan_application_status_transition
SET from_status = 'DISBURSEMENT_RETRY'
WHERE from_status = 'PAYMENT_REINITIATION';

UPDATE loan_application_status_transition
SET to_status = 'DISBURSEMENT_RETRY'
WHERE to_status = 'PAYMENT_REINITIATION';

UPDATE loan_application_audit_event
SET from_status = 'DISBURSEMENT_RETRY'
WHERE from_status = 'PAYMENT_REINITIATION';

UPDATE loan_application_audit_event
SET to_status = 'DISBURSEMENT_RETRY'
WHERE to_status = 'PAYMENT_REINITIATION';
