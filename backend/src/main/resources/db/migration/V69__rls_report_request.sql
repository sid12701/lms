-- Defence-in-depth tenant isolation for report_request (F-17).
--
-- Today, report_request is only reached via SYSTEM_ADMIN endpoints
-- (ReportAdminController) and the @Scheduled processing worker, both of
-- which run on the admin DataSource. The tenant role had no GRANT, so the
-- table was effectively dark from tenant connections.
--
-- This migration:
--   1. Grants SELECT on report_request to the tenant role so that a future
--      LSP-facing "my reports" endpoint can read from a tenant connection
--      without a follow-up schema change.
--   2. Enables RLS and adds a strict per-LSP policy so that, the moment a
--      tenant connection does reach the table, it can only see its own
--      rows. Admin-scoped rows (lsp_id IS NULL) are intentionally hidden
--      from tenants -- they remain accessible only via the admin role
--      (which bypasses RLS as the table owner), matching the loan_application
--      / loan_account isolation pattern from V41.
--
-- Writes (INSERT/UPDATE/DELETE) are deliberately not granted to the tenant
-- role today: the worker generates report bodies on the admin DataSource.
-- The FOR ALL policy still ships WITH CHECK so that, if a future migration
-- ever grants write access, the per-LSP invariant is already enforced.

GRANT SELECT ON TABLE report_request TO ${tenant_app_role};

ALTER TABLE report_request ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS report_request_tenant_policy ON report_request;

CREATE POLICY report_request_tenant_policy ON report_request
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());
