ALTER TABLE api_client
    ADD COLUMN IF NOT EXISTS previous_secret_hash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS previous_secret_valid_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_rotated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS api_client_ip_allowlist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_client_id UUID NOT NULL REFERENCES api_client(id) ON DELETE CASCADE,
    cidr VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_api_client_ip_allowlist_client_cidr UNIQUE (api_client_id, cidr)
);

CREATE INDEX IF NOT EXISTS idx_api_client_ip_allowlist_client
    ON api_client_ip_allowlist (api_client_id);

CREATE TABLE IF NOT EXISTS api_client_audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_client_id UUID NOT NULL REFERENCES api_client(id) ON DELETE CASCADE,
    actor_username VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    details_json TEXT NOT NULL,
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_api_client_audit_event_client_created
    ON api_client_audit_event (api_client_id, created_at DESC);
