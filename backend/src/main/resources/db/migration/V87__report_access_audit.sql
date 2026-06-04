CREATE TABLE IF NOT EXISTS report_access_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_username TEXT NOT NULL,
    actor_ip VARCHAR(64),
    correlation_id VARCHAR(128),
    action VARCHAR(64) NOT NULL,
    report_type VARCHAR(64) NOT NULL,
    filter_payload JSONB NOT NULL,
    byte_count BIGINT NOT NULL,
    report_request_id UUID REFERENCES report_request(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_report_access_audit_actor_created
    ON report_access_audit (actor_username, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_report_access_audit_action_created
    ON report_access_audit (action, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_report_access_audit_report_request_id
    ON report_access_audit (report_request_id);

CREATE INDEX IF NOT EXISTS idx_report_access_audit_correlation_id
    ON report_access_audit (correlation_id);
