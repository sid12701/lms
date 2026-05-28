-- F-17: Defense-in-depth tenant isolation for report_request.
--
-- report_request is currently served by SYSTEM_ADMIN endpoints and the
-- scheduled processing worker, both of which use the admin datasource.
-- Tenant connections may read their own LSP-scoped requests, but global
-- rows (lsp_id IS NULL) are admin/worker-only and remain invisible to
-- tenants.
--
-- Tenant writes are deliberately not granted. The WITH CHECK clause keeps
-- the same per-LSP invariant in place if a future migration adds writes.

GRANT SELECT ON TABLE report_request TO ${tenant_app_role};

ALTER TABLE report_request ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS report_request_tenant_policy ON report_request;

CREATE POLICY report_request_tenant_policy ON report_request
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());
