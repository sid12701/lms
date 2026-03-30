CREATE TABLE api_client (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id VARCHAR(128) NOT NULL UNIQUE,
    lsp_id UUID NOT NULL REFERENCES lsp(id) ON DELETE RESTRICT,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    secret_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_api_client_lsp_id ON api_client (lsp_id);
