CREATE TABLE IF NOT EXISTS ops_alert (
    id UUID PRIMARY KEY,
    type VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    subject_type VARCHAR(64),
    subject_id UUID,
    correlation_id VARCHAR(128),
    context_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    acknowledged_by_username VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_ops_alert_status_created_at ON ops_alert (status, created_at DESC);
