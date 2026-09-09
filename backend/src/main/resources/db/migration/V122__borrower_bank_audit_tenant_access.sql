-- C06-phase-2: let the tenant connection write its own bank-detail audit rows in the
-- same transaction as the borrower mutation, so the two commit or roll back together.
-- Previously the LSP path persisted the audit through a separate REQUIRES_NEW admin
-- transaction, which could commit the audit without the mutation (or vice versa).
--
-- Narrow by design: the tenant role gets SELECT (velocity window count) and INSERT only —
-- no UPDATE/DELETE — and row-level security confines every tenant row to its own LSP.
-- Admin-authored rows carry a NULL lsp_id and stay invisible to tenants. No unrelated
-- grants; mismatch logs and all other audit tables are untouched.

DO $$
BEGIN
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE borrower_bank_details_update_audit TO %I',
        '${tenant_app_role}'
    );
END
$$;

ALTER TABLE borrower_bank_details_update_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE borrower_bank_details_update_audit FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS borrower_bank_details_update_audit_tenant_policy
    ON borrower_bank_details_update_audit;

CREATE POLICY borrower_bank_details_update_audit_tenant_policy
    ON borrower_bank_details_update_audit
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());
