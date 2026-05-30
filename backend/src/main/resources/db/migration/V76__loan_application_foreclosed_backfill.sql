-- Unclear #1 fix: align loan_application.status with loan_account closure for
-- historic foreclosed loans. Prior to this migration, the foreclosure execution
-- path set application.status = 'CLOSED' even when loan_account.closure_reason
-- = 'FORECLOSURE'. The application enum and frontend schema already model
-- 'FORECLOSED' as a distinct terminal state; this backfill makes existing data
-- match.
UPDATE loan_application la
SET status = 'FORECLOSED'
WHERE la.status = 'CLOSED'
  AND EXISTS (
      SELECT 1
      FROM loan_account account
      WHERE account.loan_application_id = la.id
        AND account.status = 'FORECLOSED'
        AND account.closure_reason = 'FORECLOSURE'
  );

-- Repoint the recorded final status-transition row so audit history matches the
-- corrected application status. The latest CLOSED transition becomes FORECLOSED
-- only for applications we just rewrote; we identify those by joining back
-- through loan_account.
UPDATE loan_application_status_transition t
SET to_status = 'FORECLOSED'
WHERE t.to_status = 'CLOSED'
  AND t.id IN (
      SELECT DISTINCT ON (la.id) inner_t.id
      FROM loan_application la
      JOIN loan_account account ON account.loan_application_id = la.id
      JOIN loan_application_status_transition inner_t ON inner_t.loan_application_id = la.id
      WHERE la.status = 'FORECLOSED'
        AND account.status = 'FORECLOSED'
        AND account.closure_reason = 'FORECLOSURE'
        AND inner_t.to_status = 'CLOSED'
      ORDER BY la.id, inner_t.created_at DESC
  );

UPDATE loan_application_audit_event e
SET to_status = 'FORECLOSED'
WHERE e.to_status = 'CLOSED'
  AND e.id IN (
      SELECT DISTINCT ON (la.id) inner_e.id
      FROM loan_application la
      JOIN loan_account account ON account.loan_application_id = la.id
      JOIN loan_application_audit_event inner_e ON inner_e.loan_application_id = la.id
      WHERE la.status = 'FORECLOSED'
        AND account.status = 'FORECLOSED'
        AND account.closure_reason = 'FORECLOSURE'
        AND inner_e.to_status = 'CLOSED'
        AND inner_e.action = 'STATUS_TRANSITION'
      ORDER BY la.id, inner_e.created_at DESC
  );
