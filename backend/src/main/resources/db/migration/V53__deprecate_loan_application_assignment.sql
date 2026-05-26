-- Gap #19: assignment queue retired. Columns and loan_application_assignment_event
-- table are retained for forensic continuity; application code no longer writes them.

COMMENT ON COLUMN loan_application.assigned_to_username IS
    'Deprecated (Gap #19): ops assignment queue removed; column retained for forensic reads only.';

COMMENT ON COLUMN loan_application.assigned_by_username IS
    'Deprecated (Gap #19): ops assignment queue removed; column retained for forensic reads only.';

COMMENT ON COLUMN loan_application.assigned_at IS
    'Deprecated (Gap #19): ops assignment queue removed; column retained for forensic reads only.';
