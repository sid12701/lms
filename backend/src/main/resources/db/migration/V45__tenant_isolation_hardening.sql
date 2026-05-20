-- Tighten borrower RLS so only tenants that already hold visibility can
-- UPDATE/DELETE the shared borrower profile. INSERT still needs to succeed
-- before the accompanying borrower_lsp_access row is flushed in the same
-- transaction, so we keep an INSERT-specific policy.
--
-- Note on ops_alert: this table is intentionally NOT granted to the tenant
-- role. Alert writes must flip onto the admin datasource (see
-- OpsAlertService#createAlert). Keeping ops data off the tenant connection
-- prevents cross-tenant alert reads/updates at the database level.
--
-- Note on app_current_lsp_id(): we keep the hard cast so that a missing or
-- empty GUC raises loudly in logs instead of silently returning NULL. A
-- tenant connection without a bound lspId is a programming error and should
-- surface as an error, not look like an empty result set.

DROP POLICY IF EXISTS borrower_tenant_policy ON borrower;
DROP POLICY IF EXISTS borrower_tenant_select_policy ON borrower;
DROP POLICY IF EXISTS borrower_tenant_insert_policy ON borrower;
DROP POLICY IF EXISTS borrower_tenant_update_policy ON borrower;
DROP POLICY IF EXISTS borrower_tenant_delete_policy ON borrower;

CREATE POLICY borrower_tenant_select_policy ON borrower
    FOR SELECT
    TO ${tenant_app_role}
    USING (
        EXISTS (
            SELECT 1
            FROM borrower_lsp_access access
            WHERE access.borrower_id = borrower.id
              AND access.lsp_id = app_current_lsp_id()
        )
    );

CREATE POLICY borrower_tenant_insert_policy ON borrower
    FOR INSERT
    TO ${tenant_app_role}
    WITH CHECK (app_current_lsp_id() IS NOT NULL);

CREATE POLICY borrower_tenant_update_policy ON borrower
    FOR UPDATE
    TO ${tenant_app_role}
    USING (
        EXISTS (
            SELECT 1
            FROM borrower_lsp_access access
            WHERE access.borrower_id = borrower.id
              AND access.lsp_id = app_current_lsp_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM borrower_lsp_access access
            WHERE access.borrower_id = borrower.id
              AND access.lsp_id = app_current_lsp_id()
        )
    );

CREATE POLICY borrower_tenant_delete_policy ON borrower
    FOR DELETE
    TO ${tenant_app_role}
    USING (
        EXISTS (
            SELECT 1
            FROM borrower_lsp_access access
            WHERE access.borrower_id = borrower.id
              AND access.lsp_id = app_current_lsp_id()
        )
    );
