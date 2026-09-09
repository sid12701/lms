-- C01: the LSP-facing invalidation guard reads live disbursement intents through the
-- tenant connection, but disbursement_intent (V111) never received tenant grants or a
-- row-level security policy, so any tenant-scoped access fails with "permission denied
-- for table disbursement_intent". Mirror the sibling disbursement tables
-- (loan_disbursement_request_log in V41): full CRUD for the tenant role, scoped by the
-- owning loan account, with RLS enabled and forced like every other tenant table.
DO $$
BEGIN
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE disbursement_intent TO %I',
        '${tenant_app_role}'
    );
END
$$;

ALTER TABLE disbursement_intent ENABLE ROW LEVEL SECURITY;
ALTER TABLE disbursement_intent FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS disbursement_intent_tenant_policy ON disbursement_intent;

CREATE POLICY disbursement_intent_tenant_policy ON disbursement_intent
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_loan_account(loan_account_id))
    WITH CHECK (tenant_owns_loan_account(loan_account_id));
