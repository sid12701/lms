ALTER TABLE lsp
    ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE api_client
    ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS lsp_audit_event (
    id UUID PRIMARY KEY,
    lsp_id UUID NOT NULL REFERENCES lsp (id),
    actor_username VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    reason VARCHAR(64),
    note TEXT,
    cascaded_client_count INTEGER NOT NULL DEFAULT 0,
    details_json JSONB NOT NULL DEFAULT '{}',
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_lsp_audit_event_lsp_created
    ON lsp_audit_event (lsp_id, created_at DESC);
