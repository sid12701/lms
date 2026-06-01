-- Issue #64: split LSP IP allowlists by surface (UI vs API); migrate per-client rows into API list.

CREATE TABLE IF NOT EXISTS lsp_ui_ip_allowlist (
    id UUID PRIMARY KEY,
    lsp_id UUID NOT NULL REFERENCES lsp(id) ON DELETE CASCADE,
    cidr VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_lsp_ui_ip_allowlist_lsp_cidr UNIQUE (lsp_id, cidr)
);

CREATE INDEX IF NOT EXISTS ix_lsp_ui_ip_allowlist_lsp ON lsp_ui_ip_allowlist (lsp_id);

ALTER TABLE lsp
    ADD COLUMN IF NOT EXISTS enforce_ui_allowlist BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS enforce_api_allowlist BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE lsp_ip_allowlist RENAME TO lsp_api_ip_allowlist;

INSERT INTO lsp_api_ip_allowlist (id, lsp_id, cidr, description, created_at, updated_at)
SELECT gen_random_uuid(), c.lsp_id, a.cidr, NULL, a.created_at, a.created_at
FROM api_client_ip_allowlist a
         INNER JOIN api_client c ON c.id = a.api_client_id
ON CONFLICT (lsp_id, cidr) DO NOTHING;

DROP TABLE IF EXISTS api_client_ip_allowlist;
