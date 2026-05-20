CREATE TABLE loan_application_pii_reveal_audit (
    id UUID PRIMARY KEY,
    loan_application_id UUID NOT NULL REFERENCES loan_application (id),
    lsp_id UUID NOT NULL REFERENCES lsp (id),
    actor_username VARCHAR(255) NOT NULL,
    revealed_fields VARCHAR(1000) NOT NULL,
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_loan_application_pii_reveal_audit_application_created_at
    ON loan_application_pii_reveal_audit (loan_application_id, created_at DESC);

DO $$
BEGIN
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE loan_application_pii_reveal_audit TO %I',
        '${tenant_app_role}'
    );
END
$$;

ALTER TABLE loan_application_pii_reveal_audit ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS loan_application_pii_reveal_audit_tenant_policy ON loan_application_pii_reveal_audit;

CREATE POLICY loan_application_pii_reveal_audit_tenant_policy ON loan_application_pii_reveal_audit
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());
