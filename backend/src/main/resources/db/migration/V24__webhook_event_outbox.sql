CREATE TABLE webhook_event_outbox (
    id UUID PRIMARY KEY,
    lsp_id UUID NOT NULL REFERENCES lsp (id),
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json TEXT NOT NULL,
    correlation_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_webhook_event_outbox_created_at
    ON webhook_event_outbox (created_at DESC);

CREATE INDEX idx_webhook_event_outbox_lsp_created_at
    ON webhook_event_outbox (lsp_id, created_at DESC);
