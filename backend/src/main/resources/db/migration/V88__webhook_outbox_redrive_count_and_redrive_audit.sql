ALTER TABLE webhook_event_outbox
    ADD COLUMN redrive_count INT NOT NULL DEFAULT 0;

CREATE TABLE webhook_outbox_redrive_audit (
    id UUID PRIMARY KEY,
    webhook_event_id UUID NOT NULL REFERENCES webhook_event_outbox (id),
    lsp_id UUID NOT NULL REFERENCES lsp (id),
    actor_username VARCHAR(255) NOT NULL,
    actor_ip VARCHAR(64),
    correlation_id VARCHAR(128),
    redrive_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_webhook_outbox_redrive_audit_event_created
    ON webhook_outbox_redrive_audit (webhook_event_id, created_at DESC);

CREATE INDEX idx_webhook_outbox_redrive_audit_lsp_created
    ON webhook_outbox_redrive_audit (lsp_id, created_at DESC);

CREATE INDEX idx_webhook_outbox_redrive_audit_correlation_id
    ON webhook_outbox_redrive_audit (correlation_id);
