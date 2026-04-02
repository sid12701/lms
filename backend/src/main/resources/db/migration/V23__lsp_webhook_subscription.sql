ALTER TABLE lsp
    ADD COLUMN webhook_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN webhook_endpoint_url VARCHAR(500),
    ADD COLUMN webhook_signing_secret VARCHAR(255),
    ADD COLUMN webhook_event_types VARCHAR(500) NOT NULL DEFAULT '';
