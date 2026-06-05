CREATE TABLE IF NOT EXISTS auth_event_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username TEXT NOT NULL,
    user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    api_client_id UUID REFERENCES api_client(id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL,
    failure_reason VARCHAR(64),
    actor_ip VARCHAR(64),
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_auth_event_audit_username_created
    ON auth_event_audit (username, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_auth_event_audit_event_type_created
    ON auth_event_audit (event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_auth_event_audit_correlation_id
    ON auth_event_audit (correlation_id);
