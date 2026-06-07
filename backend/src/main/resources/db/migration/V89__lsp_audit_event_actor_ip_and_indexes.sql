ALTER TABLE lsp_audit_event
    ADD COLUMN IF NOT EXISTS actor_ip VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_lsp_audit_event_action_created
    ON lsp_audit_event (action, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_lsp_audit_event_actor_created
    ON lsp_audit_event (actor_username, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_lsp_audit_event_correlation_id
    ON lsp_audit_event (correlation_id);
