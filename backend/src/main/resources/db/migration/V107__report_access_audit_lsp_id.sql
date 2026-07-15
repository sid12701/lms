ALTER TABLE report_access_audit
    ADD COLUMN IF NOT EXISTS lsp_id UUID NULL REFERENCES lsp(id);

UPDATE report_access_audit
SET lsp_id = CAST(filter_payload ->> 'lspId' AS UUID)
WHERE lsp_id IS NULL
  AND filter_payload ->> 'lspId' IS NOT NULL
  AND filter_payload ->> 'lspId' ~ '^[0-9a-fA-F-]{36}$';

CREATE INDEX IF NOT EXISTS idx_report_access_audit_lsp_created
    ON report_access_audit (lsp_id, created_at DESC);
