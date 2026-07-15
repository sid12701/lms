-- Spec S19 Slice A: first-class borrower↔LSP relationship rows (metadata-ready).
-- Visibility continues to be enforced via borrower_lsp_access until a later cutover.
-- This table is dual-written on grant and backfilled from the access collection.

CREATE TABLE borrower_lsp_relationship (
    id UUID PRIMARY KEY,
    borrower_id UUID NOT NULL REFERENCES borrower (id) ON DELETE CASCADE,
    lsp_id UUID NOT NULL REFERENCES lsp (id) ON DELETE CASCADE,
    first_sourced_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_touched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_channel VARCHAR(64),
    consent_captured_at TIMESTAMPTZ,
    consent_version VARCHAR(64),
    CONSTRAINT uk_borrower_lsp_relationship UNIQUE (borrower_id, lsp_id)
);

CREATE INDEX idx_borrower_lsp_relationship_lsp ON borrower_lsp_relationship (lsp_id);
CREATE INDEX idx_borrower_lsp_relationship_borrower ON borrower_lsp_relationship (borrower_id);

INSERT INTO borrower_lsp_relationship (
    id,
    borrower_id,
    lsp_id,
    first_sourced_at,
    last_touched_at,
    source_channel
)
SELECT
    gen_random_uuid(),
    access.borrower_id,
    access.lsp_id,
    access.created_at,
    access.created_at,
    'BACKFILL'
FROM borrower_lsp_access access
ON CONFLICT (borrower_id, lsp_id) DO NOTHING;

DO $$
BEGIN
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE borrower_lsp_relationship TO %I',
        '${tenant_app_role}'
    );
END
$$;

ALTER TABLE borrower_lsp_relationship ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS borrower_lsp_relationship_tenant_policy ON borrower_lsp_relationship;

CREATE POLICY borrower_lsp_relationship_tenant_policy ON borrower_lsp_relationship
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());
