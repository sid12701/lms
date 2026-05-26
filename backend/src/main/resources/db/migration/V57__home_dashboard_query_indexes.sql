-- Gap #7 — home overview KPI query paths
CREATE INDEX IF NOT EXISTS idx_loan_application_status_transition_to_status_created_at
    ON loan_application_status_transition (to_status, created_at DESC);
