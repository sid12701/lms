CREATE TABLE IF NOT EXISTS borrower_lsp_access (
    borrower_id UUID NOT NULL REFERENCES borrower (id) ON DELETE CASCADE,
    lsp_id UUID NOT NULL REFERENCES lsp (id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (borrower_id, lsp_id)
);

CREATE TEMP TABLE canonical_borrower_map AS
SELECT DISTINCT ON (borrower.pan)
    borrower.id AS canonical_borrower_id,
    borrower.pan
FROM borrower
ORDER BY borrower.pan, borrower.updated_at DESC, borrower.created_at DESC, borrower.id;

CREATE TEMP TABLE borrower_merge_map AS
SELECT
    borrower.id AS source_borrower_id,
    canonical_borrower_map.canonical_borrower_id
FROM borrower
JOIN canonical_borrower_map ON canonical_borrower_map.pan = borrower.pan;

INSERT INTO borrower_lsp_access (borrower_id, lsp_id)
SELECT DISTINCT
    borrower_merge_map.canonical_borrower_id,
    borrower.lsp_id
FROM borrower
JOIN borrower_merge_map ON borrower_merge_map.source_borrower_id = borrower.id
ON CONFLICT (borrower_id, lsp_id) DO NOTHING;

UPDATE loan_application application
SET borrower_id = borrower_merge_map.canonical_borrower_id
FROM borrower_merge_map
WHERE borrower_merge_map.source_borrower_id = application.borrower_id;

UPDATE loan_account account
SET borrower_id = borrower_merge_map.canonical_borrower_id
FROM borrower_merge_map
WHERE borrower_merge_map.source_borrower_id = account.borrower_id;

DELETE FROM borrower borrower
USING borrower_merge_map
WHERE borrower.id = borrower_merge_map.source_borrower_id
  AND borrower_merge_map.source_borrower_id <> borrower_merge_map.canonical_borrower_id;

DROP POLICY IF EXISTS borrower_tenant_policy ON borrower;

ALTER TABLE borrower DROP CONSTRAINT IF EXISTS fk_borrower_lsp;
DROP INDEX IF EXISTS idx_borrower_lsp;
ALTER TABLE borrower DROP COLUMN IF EXISTS lsp_id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_borrower_pan ON borrower (pan);
CREATE INDEX IF NOT EXISTS idx_borrower_mobile ON borrower (mobile);
CREATE INDEX IF NOT EXISTS idx_borrower_lsp_access_lsp ON borrower_lsp_access (lsp_id);

DO $$
BEGIN
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE borrower_lsp_access TO %I', '${tenant_app_role}');
END
$$;

ALTER TABLE borrower_lsp_access ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS borrower_tenant_policy ON borrower;
DROP POLICY IF EXISTS borrower_lsp_access_tenant_policy ON borrower_lsp_access;

CREATE POLICY borrower_tenant_policy ON borrower
    FOR ALL
    TO ${tenant_app_role}
    USING (
        EXISTS (
            SELECT 1
            FROM borrower_lsp_access access
            WHERE access.borrower_id = borrower.id
              AND access.lsp_id = app_current_lsp_id()
        )
    )
    WITH CHECK (TRUE);

CREATE POLICY borrower_lsp_access_tenant_policy ON borrower_lsp_access
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());
