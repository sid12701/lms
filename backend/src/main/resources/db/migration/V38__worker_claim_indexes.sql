CREATE INDEX IF NOT EXISTS idx_report_request_status_created_at
    ON report_request (status, created_at ASC);
