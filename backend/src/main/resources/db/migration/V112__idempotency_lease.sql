ALTER TABLE lsp_api_idempotency_record
    ADD COLUMN lease_owner VARCHAR(128),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 1;

ALTER TABLE admin_api_idempotency_record
    ADD COLUMN lease_owner VARCHAR(128),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 1;

UPDATE lsp_api_idempotency_record
SET lease_expires_at = now()
WHERE response_body = '{"__idempotencyPending":true}'
  AND lease_expires_at IS NULL;

UPDATE admin_api_idempotency_record
SET lease_expires_at = now()
WHERE response_body = '{"__idempotencyPending":true}'
  AND lease_expires_at IS NULL;

CREATE INDEX idx_lsp_api_idempotency_pending_lease
    ON lsp_api_idempotency_record (lease_expires_at)
    WHERE response_body = '{"__idempotencyPending":true}';

CREATE INDEX idx_admin_api_idempotency_pending_lease
    ON admin_api_idempotency_record (lease_expires_at)
    WHERE response_body = '{"__idempotencyPending":true}';
