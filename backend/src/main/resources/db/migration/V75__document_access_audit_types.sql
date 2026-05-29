CREATE TABLE IF NOT EXISTS loan_application_document_access_audit_type (
    audit_id UUID NOT NULL REFERENCES loan_application_document_access_audit(id) ON DELETE CASCADE,
    document_type VARCHAR(64) NOT NULL,
    PRIMARY KEY (audit_id, document_type)
);

INSERT INTO loan_application_document_access_audit_type (audit_id, document_type)
SELECT
    audit.id,
    trim(split_document_type.value) AS document_type
FROM loan_application_document_access_audit audit
CROSS JOIN LATERAL regexp_split_to_table(audit.document_types, ',') AS split_document_type(value)
WHERE audit.document_types IS NOT NULL
  AND audit.document_types <> ''
  AND trim(split_document_type.value) <> ''
ON CONFLICT (audit_id, document_type) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_loan_document_access_audit_type_document_type
    ON loan_application_document_access_audit_type (document_type);

DO $$
BEGIN
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application_document_access_audit_type TO %I', '${tenant_app_role}');
END
$$;

ALTER TABLE loan_application_document_access_audit_type ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS loan_document_access_audit_type_tenant_policy
    ON loan_application_document_access_audit_type;

CREATE POLICY loan_document_access_audit_type_tenant_policy
    ON loan_application_document_access_audit_type
    FOR ALL
    TO ${tenant_app_role}
    USING (
        EXISTS (
            SELECT 1
            FROM loan_application_document_access_audit audit
            WHERE audit.id = audit_id
              AND tenant_owns_application(audit.loan_application_id)
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM loan_application_document_access_audit audit
            WHERE audit.id = audit_id
              AND tenant_owns_application(audit.loan_application_id)
        )
    );
