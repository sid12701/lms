ALTER TABLE borrower DROP CONSTRAINT IF EXISTS borrower_pan_key;

ALTER TABLE borrower ADD COLUMN IF NOT EXISTS lsp_id UUID;

CREATE TEMP TABLE borrower_clone_map AS
SELECT
    application.id AS application_id,
    gen_random_uuid() AS new_borrower_id,
    application.lsp_id,
    borrower.full_name,
    borrower.pan,
    borrower.mobile,
    borrower.email,
    borrower.date_of_birth,
    borrower.gender,
    borrower.marital_status,
    borrower.father_name,
    borrower.aadhar_number,
    borrower.city,
    borrower.state,
    borrower.address_line_1,
    borrower.address_line_2,
    borrower.address_zip_code,
    borrower.spouse_name,
    borrower.employment_type,
    borrower.organization_name,
    borrower.employee_id,
    borrower.employment_city,
    borrower.employment_state,
    borrower.employment_zip,
    borrower.monthly_income,
    borrower.annual_income,
    borrower.bank_account_number,
    borrower.bank_name,
    borrower.ifsc_code,
    borrower.account_holder_name,
    borrower.reference_person_name,
    borrower.reference_person_number,
    borrower.created_at,
    borrower.updated_at
FROM loan_application application
JOIN borrower ON borrower.id = application.borrower_id;

INSERT INTO borrower (
    id,
    lsp_id,
    full_name,
    pan,
    mobile,
    email,
    date_of_birth,
    gender,
    marital_status,
    father_name,
    aadhar_number,
    city,
    state,
    address_line_1,
    address_line_2,
    address_zip_code,
    spouse_name,
    employment_type,
    organization_name,
    employee_id,
    employment_city,
    employment_state,
    employment_zip,
    monthly_income,
    annual_income,
    bank_account_number,
    bank_name,
    ifsc_code,
    account_holder_name,
    reference_person_name,
    reference_person_number,
    created_at,
    updated_at
)
SELECT
    new_borrower_id,
    lsp_id,
    full_name,
    pan,
    mobile,
    email,
    date_of_birth,
    gender,
    marital_status,
    father_name,
    aadhar_number,
    city,
    state,
    address_line_1,
    address_line_2,
    address_zip_code,
    spouse_name,
    employment_type,
    organization_name,
    employee_id,
    employment_city,
    employment_state,
    employment_zip,
    monthly_income,
    annual_income,
    bank_account_number,
    bank_name,
    ifsc_code,
    account_holder_name,
    reference_person_name,
    reference_person_number,
    created_at,
    updated_at
FROM borrower_clone_map;

UPDATE loan_application application
SET borrower_id = borrower_clone_map.new_borrower_id
FROM borrower_clone_map
WHERE borrower_clone_map.application_id = application.id;

UPDATE loan_account account
SET borrower_id = borrower_clone_map.new_borrower_id
FROM borrower_clone_map
WHERE borrower_clone_map.application_id = account.loan_application_id;

DELETE FROM borrower borrower
WHERE borrower.lsp_id IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM loan_application application
      WHERE application.borrower_id = borrower.id
  )
  AND NOT EXISTS (
      SELECT 1
      FROM loan_account account
      WHERE account.borrower_id = borrower.id
  );

ALTER TABLE borrower
    ALTER COLUMN lsp_id SET NOT NULL;

ALTER TABLE borrower
    ADD CONSTRAINT fk_borrower_lsp
        FOREIGN KEY (lsp_id) REFERENCES lsp (id);

CREATE INDEX IF NOT EXISTS idx_borrower_lsp ON borrower (lsp_id);
CREATE INDEX IF NOT EXISTS idx_borrower_pan ON borrower (pan);

CREATE OR REPLACE FUNCTION app_current_lsp_id() RETURNS UUID
    LANGUAGE SQL
    STABLE
AS $$
    SELECT current_setting('app.current_lsp_id')::UUID
$$;

CREATE OR REPLACE FUNCTION tenant_owns_application(target_application_id UUID) RETURNS BOOLEAN
    LANGUAGE SQL
    STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM loan_application application
        WHERE application.id = target_application_id
          AND application.lsp_id = app_current_lsp_id()
    )
$$;

CREATE OR REPLACE FUNCTION tenant_owns_loan_account(target_loan_account_id UUID) RETURNS BOOLEAN
    LANGUAGE SQL
    STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM loan_account account
        WHERE account.id = target_loan_account_id
          AND account.lsp_id = app_current_lsp_id()
    )
$$;

DO $$
BEGIN
    EXECUTE format(
        'CREATE ROLE %I LOGIN PASSWORD %L',
        '${tenant_app_role}',
        '${tenant_app_password}'
    );
EXCEPTION
    WHEN duplicate_object THEN
        EXECUTE format(
            'ALTER ROLE %I WITH LOGIN PASSWORD %L',
            '${tenant_app_role}',
            '${tenant_app_password}'
        );
END
$$;

DO $$
BEGIN
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT ON TABLE lsp TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT ON TABLE loan_product TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT ON TABLE app_role TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE borrower TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_account TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application_document_checklist TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application_intake_audit TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application_status_transition TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application_assignment_event TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application_audit_event TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_application_document_access_audit TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_disbursement_request_log TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_repayment_schedule_installment TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_payment_transaction TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_foreclosure_quote TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE webhook_event_outbox TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT ON TABLE app_user TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT ON TABLE api_client TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT ON TABLE loan_product_lsp_mapping TO %I', '${tenant_app_role}');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE lsp_api_idempotency_record TO %I', '${tenant_app_role}');
END
$$;

ALTER TABLE borrower ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application_document_checklist ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application_intake_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application_status_transition ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application_assignment_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application_audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application_document_access_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_disbursement_request_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_repayment_schedule_installment ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_payment_transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_foreclosure_quote ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_event_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_client ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_product_lsp_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE lsp_api_idempotency_record ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS borrower_tenant_policy ON borrower;
DROP POLICY IF EXISTS loan_application_tenant_policy ON loan_application;
DROP POLICY IF EXISTS loan_account_tenant_policy ON loan_account;
DROP POLICY IF EXISTS loan_application_document_checklist_tenant_policy ON loan_application_document_checklist;
DROP POLICY IF EXISTS loan_application_intake_audit_tenant_policy ON loan_application_intake_audit;
DROP POLICY IF EXISTS loan_application_status_transition_tenant_policy ON loan_application_status_transition;
DROP POLICY IF EXISTS loan_application_assignment_event_tenant_policy ON loan_application_assignment_event;
DROP POLICY IF EXISTS loan_application_audit_event_tenant_policy ON loan_application_audit_event;
DROP POLICY IF EXISTS loan_application_document_access_audit_tenant_policy ON loan_application_document_access_audit;
DROP POLICY IF EXISTS loan_disbursement_request_log_tenant_policy ON loan_disbursement_request_log;
DROP POLICY IF EXISTS loan_repayment_schedule_installment_tenant_policy ON loan_repayment_schedule_installment;
DROP POLICY IF EXISTS loan_payment_transaction_tenant_policy ON loan_payment_transaction;
DROP POLICY IF EXISTS loan_foreclosure_quote_tenant_policy ON loan_foreclosure_quote;
DROP POLICY IF EXISTS webhook_event_outbox_tenant_policy ON webhook_event_outbox;
DROP POLICY IF EXISTS app_user_tenant_policy ON app_user;
DROP POLICY IF EXISTS api_client_tenant_policy ON api_client;
DROP POLICY IF EXISTS loan_product_lsp_mapping_tenant_policy ON loan_product_lsp_mapping;
DROP POLICY IF EXISTS lsp_api_idempotency_record_tenant_policy ON lsp_api_idempotency_record;

CREATE POLICY borrower_tenant_policy ON borrower
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());

CREATE POLICY loan_application_tenant_policy ON loan_application
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());

CREATE POLICY loan_account_tenant_policy ON loan_account
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());

CREATE POLICY loan_application_document_checklist_tenant_policy ON loan_application_document_checklist
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_application(loan_application_id))
    WITH CHECK (tenant_owns_application(loan_application_id));

CREATE POLICY loan_application_intake_audit_tenant_policy ON loan_application_intake_audit
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_application(loan_application_id))
    WITH CHECK (tenant_owns_application(loan_application_id));

CREATE POLICY loan_application_status_transition_tenant_policy ON loan_application_status_transition
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_application(loan_application_id))
    WITH CHECK (tenant_owns_application(loan_application_id));

CREATE POLICY loan_application_assignment_event_tenant_policy ON loan_application_assignment_event
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_application(loan_application_id))
    WITH CHECK (tenant_owns_application(loan_application_id));

CREATE POLICY loan_application_audit_event_tenant_policy ON loan_application_audit_event
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_application(loan_application_id))
    WITH CHECK (tenant_owns_application(loan_application_id));

CREATE POLICY loan_application_document_access_audit_tenant_policy ON loan_application_document_access_audit
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_application(loan_application_id))
    WITH CHECK (tenant_owns_application(loan_application_id));

CREATE POLICY loan_disbursement_request_log_tenant_policy ON loan_disbursement_request_log
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_loan_account(loan_account_id))
    WITH CHECK (tenant_owns_loan_account(loan_account_id));

CREATE POLICY loan_repayment_schedule_installment_tenant_policy ON loan_repayment_schedule_installment
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_loan_account(loan_account_id))
    WITH CHECK (tenant_owns_loan_account(loan_account_id));

CREATE POLICY loan_payment_transaction_tenant_policy ON loan_payment_transaction
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_loan_account(loan_account_id))
    WITH CHECK (tenant_owns_loan_account(loan_account_id));

CREATE POLICY loan_foreclosure_quote_tenant_policy ON loan_foreclosure_quote
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_loan_account(loan_account_id))
    WITH CHECK (tenant_owns_loan_account(loan_account_id));

CREATE POLICY webhook_event_outbox_tenant_policy ON webhook_event_outbox
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());

CREATE POLICY app_user_tenant_policy ON app_user
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());

CREATE POLICY api_client_tenant_policy ON api_client
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());

CREATE POLICY loan_product_lsp_mapping_tenant_policy ON loan_product_lsp_mapping
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());

CREATE POLICY lsp_api_idempotency_record_tenant_policy ON lsp_api_idempotency_record
    FOR ALL
    TO ${tenant_app_role}
    USING (lsp_id = app_current_lsp_id())
    WITH CHECK (lsp_id = app_current_lsp_id());
