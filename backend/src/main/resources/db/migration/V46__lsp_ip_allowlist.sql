-- Per-LSP IP allowlist for the external /api/v1/lsp/** surface.
-- Semantics: empty allowlist for an LSP means all IPs allowed (opt-in).
-- Any row present switches the LSP to strict CIDR match.
--
-- Admin-only table (not granted to the tenant role) — read/write happens on
-- the admin datasource via LspIpAllowlistFilter and the admin CRUD controller.

CREATE TABLE IF NOT EXISTS lsp_ip_allowlist (
    id UUID PRIMARY KEY,
    lsp_id UUID NOT NULL REFERENCES lsp(id) ON DELETE CASCADE,
    cidr VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (lsp_id, cidr)
);

CREATE INDEX IF NOT EXISTS ix_lsp_ip_allowlist_lsp ON lsp_ip_allowlist (lsp_id);
