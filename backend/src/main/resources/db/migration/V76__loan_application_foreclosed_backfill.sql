-- Align historic foreclosure rows with Option B: application status FORECLOSED
-- (account already FORECLOSED with closure_reason FORECLOSURE).

UPDATE loan_application la
SET status = 'FORECLOSED',
    updated_at = NOW()
FROM loan_account acct
WHERE la.id = acct.loan_application_id
  AND acct.account_status = 'FORECLOSED'
  AND acct.closure_reason = 'FORECLOSURE'
  AND la.status = 'CLOSED';

UPDATE loan_application_status_transition t
SET to_status = 'FORECLOSED'
FROM loan_account acct
JOIN loan_application la ON la.id = acct.loan_application_id
WHERE t.loan_application_id = la.id
  AND acct.account_status = 'FORECLOSED'
  AND acct.closure_reason = 'FORECLOSURE'
  AND t.to_status = 'CLOSED'
  AND t.note ILIKE '%foreclosure%';

UPDATE loan_application_audit_event e
SET to_status = 'FORECLOSED'
FROM loan_account acct
JOIN loan_application la ON la.id = acct.loan_application_id
WHERE e.loan_application_id = la.id
  AND acct.account_status = 'FORECLOSED'
  AND acct.closure_reason = 'FORECLOSURE'
  AND e.to_status = 'CLOSED'
  AND (
        e.action = 'FORECLOSURE_EXECUTED'
        OR e.note ILIKE '%foreclosure%'
      );
