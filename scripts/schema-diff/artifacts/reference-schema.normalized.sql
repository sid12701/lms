CREATE FUNCTION public.app_current_lsp_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
    SELECT current_setting('app.current_lsp_id')::UUID
$$;
CREATE FUNCTION public.tenant_owns_application(target_application_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM loan_application application
        WHERE application.id = target_application_id
          AND application.lsp_id = app_current_lsp_id()
    )
$$;
CREATE FUNCTION public.tenant_owns_loan_account(target_loan_account_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM loan_account account
        WHERE account.id = target_loan_account_id
          AND account.lsp_id = app_current_lsp_id()
    )
$$;
CREATE TABLE public.admin_api_idempotency_record (
    id uuid NOT NULL,
    operation_key character varying(64) NOT NULL,
    idempotency_key character varying(64) NOT NULL,
    request_fingerprint character varying(128) NOT NULL,
    response_status integer NOT NULL,
    response_body text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.alert_rule (
    id uuid NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(1000) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    audience character varying(32) NOT NULL,
    trigger_kind character varying(32) NOT NULL,
    config_json text,
    last_evaluated_at timestamp with time zone
);
CREATE TABLE public.api_client (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    client_id character varying(128) NOT NULL,
    lsp_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(500),
    secret_hash character varying(255) NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone,
    previous_secret_hash character varying(255),
    previous_secret_valid_until timestamp with time zone,
    last_rotated_at timestamp with time zone,
    token_version bigint DEFAULT 0 NOT NULL,
    failed_auth_attempts integer DEFAULT 0 NOT NULL,
    auth_locked_until timestamp with time zone
);
CREATE TABLE public.api_client_audit_event (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    api_client_id uuid NOT NULL,
    actor_username character varying(255) NOT NULL,
    action character varying(64) NOT NULL,
    details_json jsonb NOT NULL,
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    actor_ip character varying(64),
    CONSTRAINT chk_api_client_audit_details_json_object CHECK ((jsonb_typeof(details_json) = 'object'::text))
);
CREATE TABLE public.app_permission (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(128) NOT NULL,
    description character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.app_role (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(64) NOT NULL,
    description character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.app_role_permission (
    role_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.app_user (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    lsp_id uuid,
    username character varying(128) NOT NULL,
    email character varying(255),
    password_hash character varying(255) NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    password_change_required boolean DEFAULT false NOT NULL,
    password_changed_at timestamp with time zone DEFAULT now() NOT NULL,
    token_version bigint DEFAULT 0 NOT NULL,
    locked_at timestamp with time zone,
    lock_reason character varying(64)
);
CREATE TABLE public.app_user_audit_event (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    actor_username character varying(255) NOT NULL,
    before_state_json jsonb NOT NULL,
    after_state_json jsonb NOT NULL,
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    actor_ip character varying(64),
    CONSTRAINT chk_app_user_audit_after_state_json_object CHECK ((jsonb_typeof(after_state_json) = 'object'::text)),
    CONSTRAINT chk_app_user_audit_before_state_json_object CHECK ((jsonb_typeof(before_state_json) = 'object'::text))
);
CREATE TABLE public.app_user_role (
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.auth_event_audit (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    username text NOT NULL,
    user_id uuid,
    api_client_id uuid,
    event_type character varying(64) NOT NULL,
    failure_reason character varying(64),
    actor_ip character varying(64),
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    details_json jsonb
);
CREATE TABLE public.borrower (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    full_name character varying(255) NOT NULL,
    pan character varying(10) NOT NULL,
    mobile character varying(32) NOT NULL,
    email character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    date_of_birth date,
    city character varying(128),
    state character varying(128),
    employment_type character varying(64),
    monthly_income numeric(19,2),
    gender character varying(32),
    marital_status character varying(32),
    father_name character varying(255),
    aadhar_number character varying(12),
    address_line_1 character varying(255),
    address_line_2 character varying(255),
    address_zip_code character varying(16),
    spouse_name character varying(255),
    organization_name character varying(255),
    employee_id character varying(128),
    employment_city character varying(128),
    employment_state character varying(128),
    employment_zip character varying(16),
    annual_income numeric(19,2),
    bank_account_number character varying(64),
    bank_name character varying(255),
    ifsc_code character varying(11),
    account_holder_name character varying(255),
    reference_person_name character varying(255),
    reference_person_number character varying(32)
);
CREATE TABLE public.borrower_bank_details_update_audit (
    id uuid NOT NULL,
    borrower_id uuid NOT NULL,
    lsp_id uuid,
    actor_username character varying(255) NOT NULL,
    actor_type character varying(32) NOT NULL,
    previous_bank_account_number character varying(64),
    previous_bank_name character varying(255),
    previous_ifsc_code character varying(11),
    previous_account_holder_name character varying(255),
    new_bank_account_number character varying(64) NOT NULL,
    new_bank_name character varying(255),
    new_ifsc_code character varying(11) NOT NULL,
    new_account_holder_name character varying(255),
    client_ip character varying(64),
    correlation_id character varying(128),
    created_at timestamp with time zone NOT NULL
);
CREATE TABLE public.borrower_lsp_access (
    borrower_id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.borrower_pii_reveal_audit (
    id uuid NOT NULL,
    borrower_id uuid NOT NULL,
    lsp_id uuid,
    actor_username character varying(255) NOT NULL,
    actor_type character varying(64) NOT NULL,
    revealed_fields character varying(255) NOT NULL,
    client_ip character varying(64),
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.disbursement_outcome_audit (
    id uuid NOT NULL,
    loan_application_id uuid NOT NULL,
    loan_account_id uuid NOT NULL,
    actor_username character varying(255) NOT NULL,
    actor_ip character varying(64),
    correlation_id character varying(128),
    source character varying(32) NOT NULL,
    outcome character varying(32) NOT NULL,
    provider_request_id character varying(128),
    created_at timestamp with time zone NOT NULL
);
CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);
CREATE TABLE public.loan_account (
    id uuid NOT NULL,
    loan_application_id uuid NOT NULL,
    borrower_id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    loan_product_id uuid NOT NULL,
    account_number character varying(64) NOT NULL,
    principal_amount numeric(19,2) NOT NULL,
    tenure_months integer NOT NULL,
    status character varying(64) NOT NULL,
    approved_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    closure_reason character varying(32),
    closed_at timestamp with time zone,
    closed_by_username character varying(255),
    disbursed_at timestamp with time zone,
    entity_version bigint DEFAULT 0 NOT NULL,
    processing_fee_amount numeric(19,2),
    loan_product_version_id uuid NOT NULL,
    CONSTRAINT chk_loan_account_principal_non_negative CHECK ((principal_amount >= (0)::numeric)),
    CONSTRAINT chk_loan_account_tenure_positive CHECK ((tenure_months > 0))
);
CREATE TABLE public.loan_application (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    borrower_id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    loan_product_id uuid NOT NULL,
    external_loan_id character varying(128) NOT NULL,
    source_channel character varying(64) NOT NULL,
    requested_amount numeric(19,2) NOT NULL,
    tenure_months integer NOT NULL,
    status character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    assigned_to_username character varying(128),
    assigned_by_username character varying(128),
    assigned_at timestamp with time zone,
    entity_version bigint DEFAULT 0 NOT NULL,
    invalid_reason_code character varying(64),
    invalid_reason_text character varying(500),
    invalidated_by_username character varying(255),
    invalidated_at timestamp with time zone,
    loan_product_version_id uuid NOT NULL,
    CONSTRAINT chk_loan_application_requested_amount_non_negative CHECK ((requested_amount >= (0)::numeric)),
    CONSTRAINT chk_loan_application_tenure_positive CHECK ((tenure_months > 0))
);
CREATE TABLE public.loan_application_assignment_event (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    loan_application_id uuid NOT NULL,
    from_assignee_username character varying(128),
    to_assignee_username character varying(128),
    actor_username character varying(128) NOT NULL,
    note character varying(500),
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.loan_application_audit_event (
    id uuid NOT NULL,
    loan_application_id uuid NOT NULL,
    action character varying(64) NOT NULL,
    actor_username character varying(255) NOT NULL,
    from_status character varying(64) NOT NULL,
    to_status character varying(64) NOT NULL,
    note character varying(500) NOT NULL,
    reason_code character varying(64),
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE TABLE public.loan_application_document_access_audit (
    id uuid NOT NULL,
    loan_application_id uuid NOT NULL,
    action character varying(64) NOT NULL,
    actor_username character varying(255) NOT NULL,
    summary character varying(500) NOT NULL,
    document_types character varying(500) NOT NULL,
    correlation_id character varying(128),
    created_at timestamp with time zone NOT NULL,
    actor_ip character varying(64),
    byte_count bigint
);
CREATE TABLE public.loan_application_document_access_audit_type (
    audit_id uuid NOT NULL,
    document_type character varying(64) NOT NULL
);
CREATE TABLE public.loan_application_document_checklist (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    loan_application_id uuid NOT NULL,
    document_type character varying(64) NOT NULL,
    required boolean NOT NULL,
    status character varying(32) NOT NULL,
    note character varying(500),
    updated_by_username character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    file_name character varying(255),
    file_reference character varying(500),
    source_reference character varying(500),
    content_type character varying(128),
    uploaded_at timestamp with time zone,
    uploaded_by_username character varying(128),
    lms_managed_content boolean DEFAULT false NOT NULL,
    storage_key character varying(500),
    file_checksum character varying(128),
    file_size_bytes bigint
);
CREATE TABLE public.loan_application_intake_audit (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    loan_application_id uuid NOT NULL,
    actor_username character varying(255) NOT NULL,
    correlation_id character varying(128),
    payload_json jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_loan_application_intake_audit_payload_json_object CHECK ((jsonb_typeof(payload_json) = 'object'::text))
);
CREATE TABLE public.loan_application_pii_reveal_audit (
    id uuid NOT NULL,
    loan_application_id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    actor_username character varying(255) NOT NULL,
    revealed_fields character varying(1000) NOT NULL,
    correlation_id character varying(128),
    created_at timestamp with time zone NOT NULL
);
CREATE TABLE public.loan_application_status_transition (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    loan_application_id uuid NOT NULL,
    from_status character varying(32) NOT NULL,
    to_status character varying(32) NOT NULL,
    actor_username character varying(255) NOT NULL,
    note character varying(500) NOT NULL,
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    reason_code character varying(64),
    rejection_reason_json jsonb,
    CONSTRAINT chk_status_transition_rejection_reason_json_object CHECK (((rejection_reason_json IS NULL) OR (jsonb_typeof(rejection_reason_json) = 'object'::text)))
);
CREATE TABLE public.loan_delinquency_state (
    id uuid NOT NULL,
    loan_application_id uuid NOT NULL,
    last_bucket character varying(32) NOT NULL,
    last_max_days_past_due integer NOT NULL,
    last_evaluated_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.loan_disbursement_bank_mismatch_log (
    id uuid NOT NULL,
    loan_application_id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    submitted_bank_account_number character varying(64),
    submitted_ifsc_code character varying(11),
    submitted_account_holder_name character varying(255),
    correlation_id character varying(128),
    created_at timestamp with time zone NOT NULL,
    soft boolean DEFAULT false NOT NULL
);
CREATE TABLE public.loan_disbursement_request_log (
    id uuid NOT NULL,
    loan_account_id uuid NOT NULL,
    actor_username character varying(255) NOT NULL,
    amount numeric(19,2) NOT NULL,
    provider_name character varying(64) NOT NULL,
    provider_request_id character varying(128) NOT NULL,
    provider_status character varying(64) NOT NULL,
    request_payload_json jsonb NOT NULL,
    response_payload_json jsonb NOT NULL,
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    payment_mode character varying(16),
    tran_ref_no character varying(64),
    provider_act_code character varying(16),
    bank_rrn character varying(32),
    decline_kind character varying(16),
    status_check_count integer DEFAULT 0 NOT NULL,
    CONSTRAINT chk_disbursement_request_payload_json_object CHECK ((jsonb_typeof(request_payload_json) = 'object'::text)),
    CONSTRAINT chk_disbursement_response_payload_json_object CHECK ((jsonb_typeof(response_payload_json) = 'object'::text))
);
CREATE TABLE public.loan_foreclosure_quote (
    id uuid NOT NULL,
    loan_account_id uuid NOT NULL,
    version integer NOT NULL,
    requested_by_username character varying(255) NOT NULL,
    executed_by_username character varying(255),
    effective_date date NOT NULL,
    outstanding_principal numeric(19,2) NOT NULL,
    outstanding_interest numeric(19,2) NOT NULL,
    settlement_amount numeric(19,2) NOT NULL,
    status character varying(32) NOT NULL,
    executed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.loan_payment_transaction (
    id uuid NOT NULL,
    loan_account_id uuid NOT NULL,
    actor_username character varying(255) NOT NULL,
    amount numeric(19,2) NOT NULL,
    payment_date date NOT NULL,
    reference character varying(128),
    channel character varying(64) NOT NULL,
    status character varying(64) NOT NULL,
    note character varying(500),
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    allocated_amount numeric(19,2) DEFAULT 0 NOT NULL,
    unallocated_amount numeric(19,2) DEFAULT 0 NOT NULL,
    repayment_installment_id uuid,
    idempotency_key character varying(36),
    request_fingerprint character varying(64),
    CONSTRAINT chk_loan_payment_allocated_non_negative CHECK ((allocated_amount >= (0)::numeric)),
    CONSTRAINT chk_loan_payment_allocation_total CHECK (((allocated_amount + unallocated_amount) = amount)),
    CONSTRAINT chk_loan_payment_amount_non_negative CHECK ((amount >= (0)::numeric)),
    CONSTRAINT chk_loan_payment_unallocated_non_negative CHECK ((unallocated_amount >= (0)::numeric))
);
CREATE TABLE public.loan_product (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    min_principal numeric(19,2) NOT NULL,
    max_principal numeric(19,2) NOT NULL,
    interest_rate numeric(5,2) NOT NULL,
    processing_fee_rate numeric(5,2) NOT NULL,
    min_tenure_months integer NOT NULL,
    max_tenure_months integer NOT NULL,
    status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_loan_product_interest_rate_non_negative CHECK ((interest_rate >= (0)::numeric)),
    CONSTRAINT chk_loan_product_max_principal_non_negative CHECK ((max_principal >= (0)::numeric)),
    CONSTRAINT chk_loan_product_max_tenure_positive CHECK ((max_tenure_months > 0)),
    CONSTRAINT chk_loan_product_min_principal_non_negative CHECK ((min_principal >= (0)::numeric)),
    CONSTRAINT chk_loan_product_min_tenure_positive CHECK ((min_tenure_months > 0)),
    CONSTRAINT chk_loan_product_principal_range CHECK ((min_principal <= max_principal)),
    CONSTRAINT chk_loan_product_processing_fee_non_negative CHECK ((processing_fee_rate >= (0)::numeric)),
    CONSTRAINT chk_loan_product_tenure_range CHECK ((min_tenure_months <= max_tenure_months))
);
CREATE TABLE public.loan_product_audit_event (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    loan_product_id uuid NOT NULL,
    action character varying(64) NOT NULL,
    actor_username character varying(255) NOT NULL,
    summary character varying(500) NOT NULL,
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.loan_product_lsp_mapping (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    loan_product_id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.loan_product_version (
    id uuid NOT NULL,
    loan_product_id uuid NOT NULL,
    version_number integer NOT NULL,
    min_principal numeric(19,2) NOT NULL,
    max_principal numeric(19,2) NOT NULL,
    interest_rate numeric(5,2) NOT NULL,
    processing_fee_rate numeric(5,2) NOT NULL,
    min_tenure_months integer NOT NULL,
    max_tenure_months integer NOT NULL,
    effective_from timestamp with time zone NOT NULL,
    created_by character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.loan_repayment_schedule_installment (
    id uuid NOT NULL,
    loan_account_id uuid NOT NULL,
    installment_number integer NOT NULL,
    due_date date NOT NULL,
    opening_principal numeric(19,2) NOT NULL,
    principal_due numeric(19,2) NOT NULL,
    interest_due numeric(19,2) NOT NULL,
    installment_amount numeric(19,2) NOT NULL,
    closing_principal numeric(19,2) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    status character varying(64) DEFAULT 'PENDING'::character varying NOT NULL,
    paid_principal numeric(19,2) DEFAULT 0 NOT NULL,
    paid_interest numeric(19,2) DEFAULT 0 NOT NULL,
    paid_amount numeric(19,2) DEFAULT 0 NOT NULL,
    outstanding_amount numeric(19,2) DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    entity_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_installment_amount_non_negative CHECK ((installment_amount >= (0)::numeric)),
    CONSTRAINT chk_installment_closing_principal_non_negative CHECK ((closing_principal >= (0)::numeric)),
    CONSTRAINT chk_installment_interest_due_non_negative CHECK ((interest_due >= (0)::numeric)),
    CONSTRAINT chk_installment_number_positive CHECK ((installment_number > 0)),
    CONSTRAINT chk_installment_opening_principal_non_negative CHECK ((opening_principal >= (0)::numeric)),
    CONSTRAINT chk_installment_outstanding_non_negative CHECK ((outstanding_amount >= (0)::numeric)),
    CONSTRAINT chk_installment_paid_amount_non_negative CHECK ((paid_amount >= (0)::numeric)),
    CONSTRAINT chk_installment_paid_interest_non_negative CHECK ((paid_interest >= (0)::numeric)),
    CONSTRAINT chk_installment_paid_principal_non_negative CHECK ((paid_principal >= (0)::numeric)),
    CONSTRAINT chk_installment_paid_sum CHECK ((paid_amount = (paid_principal + paid_interest))),
    CONSTRAINT chk_installment_principal_due_non_negative CHECK ((principal_due >= (0)::numeric)),
    CONSTRAINT chk_installment_total CHECK (((paid_amount + outstanding_amount) = installment_amount))
);
CREATE TABLE public.lsp (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    webhook_enabled boolean DEFAULT false NOT NULL,
    webhook_endpoint_url character varying(500),
    webhook_signing_secret character varying(255),
    webhook_event_types character varying(500) DEFAULT ''::character varying NOT NULL,
    token_version bigint DEFAULT 0 NOT NULL,
    enforce_ui_allowlist boolean DEFAULT false NOT NULL,
    enforce_api_allowlist boolean DEFAULT false NOT NULL
);
CREATE TABLE public.lsp_api_idempotency_record (
    id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    operation_key character varying(64) NOT NULL,
    idempotency_key character varying(64) NOT NULL,
    request_fingerprint character varying(128) NOT NULL,
    response_status integer NOT NULL,
    response_body text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);
CREATE TABLE public.lsp_api_ip_allowlist (
    id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    cidr character varying(64) NOT NULL,
    description character varying(255),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);
CREATE TABLE public.lsp_audit_event (
    id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    actor_username character varying(255) NOT NULL,
    action character varying(64) NOT NULL,
    reason character varying(64),
    note text,
    cascaded_client_count integer DEFAULT 0 NOT NULL,
    details_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    correlation_id character varying(128),
    created_at timestamp with time zone NOT NULL,
    actor_ip character varying(64)
);
CREATE TABLE public.lsp_ui_ip_allowlist (
    id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    cidr character varying(64) NOT NULL,
    description character varying(255),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);
CREATE TABLE public.ops_alert (
    id uuid NOT NULL,
    type character varying(64) NOT NULL,
    severity character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    title character varying(255) NOT NULL,
    message character varying(1000) NOT NULL,
    subject_type character varying(64),
    subject_id uuid,
    correlation_id character varying(128),
    context_json jsonb,
    created_at timestamp with time zone NOT NULL,
    acknowledged_at timestamp with time zone,
    acknowledged_by_username character varying(255),
    acknowledgement_note character varying(500),
    CONSTRAINT chk_ops_alert_context_json_object CHECK (((context_json IS NULL) OR (jsonb_typeof(context_json) = 'object'::text)))
);
CREATE TABLE public.portfolio_kpi_snapshot (
    id uuid NOT NULL,
    lsp_id uuid,
    computed_at timestamp with time zone NOT NULL,
    total_disbursed numeric(19,2) NOT NULL,
    total_outstanding numeric(19,2) NOT NULL,
    total_overdue numeric(19,2) NOT NULL,
    status_counts jsonb NOT NULL,
    dpd_buckets jsonb NOT NULL,
    avg_approval_tat_hours numeric(10,2),
    CONSTRAINT chk_portfolio_kpi_dpd_buckets_object CHECK ((jsonb_typeof(dpd_buckets) = 'object'::text)),
    CONSTRAINT chk_portfolio_kpi_status_counts_object CHECK ((jsonb_typeof(status_counts) = 'object'::text))
);
CREATE TABLE public.refresh_token (
    id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    auth_type character varying(32) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    app_user_id uuid,
    api_client_id uuid,
    CONSTRAINT chk_refresh_token_subject_xor CHECK (((((auth_type)::text = 'PASSWORD'::text) AND (app_user_id IS NOT NULL) AND (api_client_id IS NULL)) OR (((auth_type)::text = 'API_CLIENT'::text) AND (api_client_id IS NOT NULL) AND (app_user_id IS NULL))))
);
CREATE TABLE public.report_access_audit (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    actor_username text NOT NULL,
    actor_ip character varying(64),
    correlation_id character varying(128),
    action character varying(64) NOT NULL,
    report_type character varying(64) NOT NULL,
    filter_payload jsonb NOT NULL,
    byte_count bigint NOT NULL,
    report_request_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    lsp_id uuid
);
CREATE TABLE public.report_request (
    id uuid NOT NULL,
    report_type character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    lsp_id uuid,
    disbursal_date_from date,
    disbursal_date_to date,
    requested_by_username character varying(255) NOT NULL,
    file_name character varying(255),
    media_type character varying(128),
    report_content text,
    error_message character varying(1000),
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    notification_email character varying(255),
    notification_sent_at timestamp with time zone,
    notification_error_message character varying(1000),
    entity_version bigint DEFAULT 0 NOT NULL,
    storage_key character varying(500)
);
CREATE TABLE public.webhook_event_delivery_attempt (
    id uuid NOT NULL,
    outbox_event_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    request_url character varying(500) NOT NULL,
    response_status_code integer,
    response_body text,
    error_message text,
    status character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    request_event_type character varying(64) NOT NULL,
    request_delivery_id character varying(64) NOT NULL,
    request_timestamp character varying(64) NOT NULL,
    request_signature character varying(255) NOT NULL
);
CREATE TABLE public.webhook_event_outbox (
    id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    event_type character varying(64) NOT NULL,
    aggregate_type character varying(64) NOT NULL,
    aggregate_id character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    payload_json jsonb NOT NULL,
    correlation_id character varying(128),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    last_attempt_at timestamp with time zone,
    next_attempt_at timestamp with time zone,
    delivered_at timestamp with time zone,
    last_error character varying(1000),
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    entity_version bigint DEFAULT 0 NOT NULL,
    loan_application_id uuid,
    claim_expires_at timestamp with time zone,
    redrive_count integer DEFAULT 0 NOT NULL,
    CONSTRAINT chk_webhook_event_outbox_payload_json_object CHECK ((jsonb_typeof(payload_json) = 'object'::text))
);
CREATE TABLE public.webhook_outbox_redrive_audit (
    id uuid NOT NULL,
    webhook_event_id uuid NOT NULL,
    lsp_id uuid NOT NULL,
    actor_username character varying(255) NOT NULL,
    actor_ip character varying(64),
    correlation_id character varying(128),
    redrive_count integer NOT NULL,
    created_at timestamp with time zone NOT NULL
);
ALTER TABLE ONLY public.admin_api_idempotency_record
    ADD CONSTRAINT admin_api_idempotency_record_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_code_key UNIQUE (code);
ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.api_client_audit_event
    ADD CONSTRAINT api_client_audit_event_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.api_client
    ADD CONSTRAINT api_client_client_id_key UNIQUE (client_id);
ALTER TABLE ONLY public.api_client
    ADD CONSTRAINT api_client_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.app_permission
    ADD CONSTRAINT app_permission_code_key UNIQUE (code);
ALTER TABLE ONLY public.app_permission
    ADD CONSTRAINT app_permission_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.app_role
    ADD CONSTRAINT app_role_code_key UNIQUE (code);
ALTER TABLE ONLY public.app_role_permission
    ADD CONSTRAINT app_role_permission_pkey PRIMARY KEY (role_id, permission_id);
ALTER TABLE ONLY public.app_role
    ADD CONSTRAINT app_role_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.app_user_audit_event
    ADD CONSTRAINT app_user_audit_event_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_email_key UNIQUE (email);
ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.app_user_role
    ADD CONSTRAINT app_user_role_pkey PRIMARY KEY (user_id, role_id);
ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_username_key UNIQUE (username);
ALTER TABLE ONLY public.auth_event_audit
    ADD CONSTRAINT auth_event_audit_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.borrower_bank_details_update_audit
    ADD CONSTRAINT borrower_bank_details_update_audit_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.borrower_lsp_access
    ADD CONSTRAINT borrower_lsp_access_pkey PRIMARY KEY (borrower_id, lsp_id);
ALTER TABLE ONLY public.borrower_pii_reveal_audit
    ADD CONSTRAINT borrower_pii_reveal_audit_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.borrower
    ADD CONSTRAINT borrower_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.disbursement_outcome_audit
    ADD CONSTRAINT disbursement_outcome_audit_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);
ALTER TABLE ONLY public.loan_account
    ADD CONSTRAINT loan_account_account_number_key UNIQUE (account_number);
ALTER TABLE ONLY public.loan_account
    ADD CONSTRAINT loan_account_loan_application_id_key UNIQUE (loan_application_id);
ALTER TABLE ONLY public.loan_account
    ADD CONSTRAINT loan_account_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_application_assignment_event
    ADD CONSTRAINT loan_application_assignment_event_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_application_audit_event
    ADD CONSTRAINT loan_application_audit_event_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_application_document_access_audit
    ADD CONSTRAINT loan_application_document_access_audit_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_application_document_access_audit_type
    ADD CONSTRAINT loan_application_document_access_audit_type_pkey PRIMARY KEY (audit_id, document_type);
ALTER TABLE ONLY public.loan_application_document_checklist
    ADD CONSTRAINT loan_application_document_checklist_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_application_intake_audit
    ADD CONSTRAINT loan_application_intake_audit_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_application_pii_reveal_audit
    ADD CONSTRAINT loan_application_pii_reveal_audit_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_application
    ADD CONSTRAINT loan_application_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_application_status_transition
    ADD CONSTRAINT loan_application_status_transition_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_delinquency_state
    ADD CONSTRAINT loan_delinquency_state_loan_application_id_key UNIQUE (loan_application_id);
ALTER TABLE ONLY public.loan_delinquency_state
    ADD CONSTRAINT loan_delinquency_state_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_disbursement_bank_mismatch_log
    ADD CONSTRAINT loan_disbursement_bank_mismatch_log_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_disbursement_request_log
    ADD CONSTRAINT loan_disbursement_request_log_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_foreclosure_quote
    ADD CONSTRAINT loan_foreclosure_quote_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_payment_transaction
    ADD CONSTRAINT loan_payment_transaction_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_product_audit_event
    ADD CONSTRAINT loan_product_audit_event_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_product
    ADD CONSTRAINT loan_product_code_key UNIQUE (code);
ALTER TABLE ONLY public.loan_product_lsp_mapping
    ADD CONSTRAINT loan_product_lsp_mapping_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_product
    ADD CONSTRAINT loan_product_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_product_version
    ADD CONSTRAINT loan_product_version_loan_product_id_version_number_key UNIQUE (loan_product_id, version_number);
ALTER TABLE ONLY public.loan_product_version
    ADD CONSTRAINT loan_product_version_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_repayment_schedule_installment
    ADD CONSTRAINT loan_repayment_schedule_installment_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.lsp_api_idempotency_record
    ADD CONSTRAINT lsp_api_idempotency_record_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.lsp_audit_event
    ADD CONSTRAINT lsp_audit_event_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.lsp
    ADD CONSTRAINT lsp_code_key UNIQUE (code);
ALTER TABLE ONLY public.lsp_api_ip_allowlist
    ADD CONSTRAINT lsp_ip_allowlist_lsp_id_cidr_key UNIQUE (lsp_id, cidr);
ALTER TABLE ONLY public.lsp_api_ip_allowlist
    ADD CONSTRAINT lsp_ip_allowlist_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.lsp
    ADD CONSTRAINT lsp_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.lsp_ui_ip_allowlist
    ADD CONSTRAINT lsp_ui_ip_allowlist_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.ops_alert
    ADD CONSTRAINT ops_alert_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.portfolio_kpi_snapshot
    ADD CONSTRAINT portfolio_kpi_snapshot_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT refresh_token_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT refresh_token_token_hash_key UNIQUE (token_hash);
ALTER TABLE ONLY public.report_access_audit
    ADD CONSTRAINT report_access_audit_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.report_request
    ADD CONSTRAINT report_request_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.loan_application
    ADD CONSTRAINT uk_loan_application_lsp_external UNIQUE (lsp_id, external_loan_id);
ALTER TABLE ONLY public.loan_payment_transaction
    ADD CONSTRAINT uk_loan_payment_transaction_idempotency_key UNIQUE (idempotency_key);
ALTER TABLE ONLY public.loan_product_lsp_mapping
    ADD CONSTRAINT uk_loan_product_lsp_mapping UNIQUE (loan_product_id, lsp_id);
ALTER TABLE ONLY public.lsp_ui_ip_allowlist
    ADD CONSTRAINT uk_lsp_ui_ip_allowlist_lsp_cidr UNIQUE (lsp_id, cidr);
ALTER TABLE ONLY public.loan_application_document_checklist
    ADD CONSTRAINT uq_loan_application_document_checklist_application_type UNIQUE (loan_application_id, document_type);
ALTER TABLE ONLY public.webhook_event_delivery_attempt
    ADD CONSTRAINT webhook_event_delivery_attempt_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.webhook_event_outbox
    ADD CONSTRAINT webhook_event_outbox_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.webhook_outbox_redrive_audit
    ADD CONSTRAINT webhook_outbox_redrive_audit_pkey PRIMARY KEY (id);
CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);
CREATE INDEX idx_admin_api_idempotency_created_at ON public.admin_api_idempotency_record USING btree (created_at DESC);
CREATE INDEX idx_api_client_audit_event_client_created ON public.api_client_audit_event USING btree (api_client_id, created_at DESC);
CREATE INDEX idx_api_client_lsp_id ON public.api_client USING btree (lsp_id);
CREATE INDEX idx_app_user_audit_event_user_created ON public.app_user_audit_event USING btree (user_id, created_at DESC);
CREATE INDEX idx_app_user_lsp_username ON public.app_user USING btree (lsp_id, username);
CREATE INDEX idx_auth_event_audit_correlation_id ON public.auth_event_audit USING btree (correlation_id);
CREATE INDEX idx_auth_event_audit_event_type_created ON public.auth_event_audit USING btree (event_type, created_at DESC);
CREATE INDEX idx_auth_event_audit_login_failed_username_ip_created ON public.auth_event_audit USING btree (username, actor_ip, created_at DESC) WHERE ((event_type)::text = 'LOGIN_FAILED'::text);
CREATE INDEX idx_auth_event_audit_username_created ON public.auth_event_audit USING btree (username, created_at DESC);
CREATE INDEX idx_borrower_bank_details_audit_borrower_created ON public.borrower_bank_details_update_audit USING btree (borrower_id, created_at DESC);
CREATE INDEX idx_borrower_full_name_trgm ON public.borrower USING gin (lower((full_name)::text) public.gin_trgm_ops);
CREATE INDEX idx_borrower_lsp_access_lsp ON public.borrower_lsp_access USING btree (lsp_id);
CREATE INDEX idx_borrower_mobile ON public.borrower USING btree (mobile);
CREATE INDEX idx_borrower_mobile_trgm ON public.borrower USING gin (lower((mobile)::text) public.gin_trgm_ops);
CREATE INDEX idx_borrower_pan_trgm ON public.borrower USING gin (lower((pan)::text) public.gin_trgm_ops);
CREATE INDEX idx_borrower_pii_reveal_audit_borrower_created ON public.borrower_pii_reveal_audit USING btree (borrower_id, created_at DESC);
CREATE INDEX idx_disbursement_bank_mismatch_app_lsp_created ON public.loan_disbursement_bank_mismatch_log USING btree (loan_application_id, lsp_id, created_at DESC);
CREATE INDEX idx_disbursement_outcome_audit_actor_created ON public.disbursement_outcome_audit USING btree (actor_username, created_at DESC);
CREATE INDEX idx_disbursement_outcome_audit_correlation_id ON public.disbursement_outcome_audit USING btree (correlation_id);
CREATE INDEX idx_disbursement_outcome_audit_loan_app_created ON public.disbursement_outcome_audit USING btree (loan_application_id, created_at DESC);
CREATE INDEX idx_disbursement_outcome_audit_source_created ON public.disbursement_outcome_audit USING btree (source, created_at DESC);
CREATE INDEX idx_installment_overdue_lookup ON public.loan_repayment_schedule_installment USING btree (loan_account_id, due_date) WHERE (outstanding_amount > (0)::numeric);
CREATE INDEX idx_loan_account_borrower ON public.loan_account USING btree (borrower_id);
CREATE INDEX idx_loan_account_disbursed_at ON public.loan_account USING btree (disbursed_at) WHERE (disbursed_at IS NOT NULL);
CREATE INDEX idx_loan_account_loan_product_version_id ON public.loan_account USING btree (loan_product_version_id);
CREATE INDEX idx_loan_account_lsp ON public.loan_account USING btree (lsp_id);
CREATE INDEX idx_loan_account_lsp_disbursed_created_at ON public.loan_account USING btree (lsp_id, disbursed_at DESC, created_at DESC);
CREATE INDEX idx_loan_application_assignment_event_application_created_at ON public.loan_application_assignment_event USING btree (loan_application_id, created_at DESC);
CREATE INDEX idx_loan_application_audit_event_actor_created_at ON public.loan_application_audit_event USING btree (actor_username, created_at DESC);
CREATE INDEX idx_loan_application_audit_event_application_created_at ON public.loan_application_audit_event USING btree (loan_application_id, created_at DESC);
CREATE INDEX idx_loan_application_audit_event_created_at ON public.loan_application_audit_event USING btree (created_at DESC);
CREATE INDEX idx_loan_application_borrower_id ON public.loan_application USING btree (borrower_id);
CREATE INDEX idx_loan_application_document_checklist_application_created_at ON public.loan_application_document_checklist USING btree (loan_application_id, created_at);
CREATE INDEX idx_loan_application_external_loan_id_trgm ON public.loan_application USING gin (lower((external_loan_id)::text) public.gin_trgm_ops);
CREATE INDEX idx_loan_application_intake_audit_actor_created_at ON public.loan_application_intake_audit USING btree (actor_username, created_at DESC);
CREATE INDEX idx_loan_application_intake_audit_created_at ON public.loan_application_intake_audit USING btree (loan_application_id, created_at DESC);
CREATE INDEX idx_loan_application_intake_audit_global_created_at ON public.loan_application_intake_audit USING btree (created_at DESC);
CREATE INDEX idx_loan_application_invalidated_at ON public.loan_application USING btree (invalidated_at DESC);
CREATE INDEX idx_loan_application_loan_product_version_id ON public.loan_application USING btree (loan_product_version_id);
CREATE INDEX idx_loan_application_lsp_created_at ON public.loan_application USING btree (lsp_id, created_at DESC);
CREATE INDEX idx_loan_application_pii_reveal_audit_application_created_at ON public.loan_application_pii_reveal_audit USING btree (loan_application_id, created_at DESC);
CREATE INDEX idx_loan_application_product_created_at ON public.loan_application USING btree (loan_product_id, created_at DESC);
CREATE INDEX idx_loan_application_source_channel_created_at ON public.loan_application USING btree (source_channel, created_at DESC);
CREATE INDEX idx_loan_application_status_created_at ON public.loan_application USING btree (status, created_at DESC);
CREATE INDEX idx_loan_application_status_transition_application_created_at ON public.loan_application_status_transition USING btree (loan_application_id, created_at DESC);
CREATE INDEX idx_loan_application_status_transition_to_status_created_at ON public.loan_application_status_transition USING btree (to_status, created_at DESC);
CREATE INDEX idx_loan_disbursement_request_log_account_created_at ON public.loan_disbursement_request_log USING btree (loan_account_id, created_at DESC);
CREATE INDEX idx_loan_document_access_audit_actor_created_at ON public.loan_application_document_access_audit USING btree (actor_username, created_at DESC);
CREATE INDEX idx_loan_document_access_audit_application_created_at ON public.loan_application_document_access_audit USING btree (loan_application_id, created_at DESC);
CREATE INDEX idx_loan_document_access_audit_created_at ON public.loan_application_document_access_audit USING btree (created_at DESC);
CREATE INDEX idx_loan_document_access_audit_type_document_type ON public.loan_application_document_access_audit_type USING btree (document_type);
CREATE UNIQUE INDEX idx_loan_foreclosure_quote_account_version ON public.loan_foreclosure_quote USING btree (loan_account_id, version);
CREATE INDEX idx_loan_payment_transaction_account_payment_date ON public.loan_payment_transaction USING btree (loan_account_id, payment_date DESC, created_at DESC);
CREATE INDEX idx_loan_payment_transaction_installment ON public.loan_payment_transaction USING btree (repayment_installment_id);
CREATE INDEX idx_loan_product_audit_event_actor_created_at ON public.loan_product_audit_event USING btree (actor_username, created_at DESC);
CREATE INDEX idx_loan_product_audit_event_created_at ON public.loan_product_audit_event USING btree (created_at DESC);
CREATE INDEX idx_loan_product_audit_event_product_created_at ON public.loan_product_audit_event USING btree (loan_product_id, created_at DESC);
CREATE UNIQUE INDEX idx_loan_repayment_schedule_account_installment ON public.loan_repayment_schedule_installment USING btree (loan_account_id, installment_number);
CREATE INDEX idx_lsp_api_idempotency_created_at ON public.lsp_api_idempotency_record USING btree (created_at DESC);
CREATE INDEX idx_lsp_audit_event_action_created ON public.lsp_audit_event USING btree (action, created_at DESC);
CREATE INDEX idx_lsp_audit_event_actor_created ON public.lsp_audit_event USING btree (actor_username, created_at DESC);
CREATE INDEX idx_lsp_audit_event_correlation_id ON public.lsp_audit_event USING btree (correlation_id);
CREATE INDEX idx_lsp_audit_event_lsp_created ON public.lsp_audit_event USING btree (lsp_id, created_at DESC);
CREATE INDEX idx_ops_alert_status_created_at ON public.ops_alert USING btree (status, created_at DESC);
CREATE INDEX idx_portfolio_kpi_snapshot_lsp_computed ON public.portfolio_kpi_snapshot USING btree (lsp_id, computed_at DESC);
CREATE INDEX idx_refresh_token_api_client_id ON public.refresh_token USING btree (api_client_id) WHERE (api_client_id IS NOT NULL);
CREATE INDEX idx_refresh_token_app_user_id ON public.refresh_token USING btree (app_user_id) WHERE (app_user_id IS NOT NULL);
CREATE INDEX idx_refresh_token_expires ON public.refresh_token USING btree (expires_at);
CREATE INDEX idx_report_access_audit_action_created ON public.report_access_audit USING btree (action, created_at DESC);
CREATE INDEX idx_report_access_audit_actor_created ON public.report_access_audit USING btree (actor_username, created_at DESC);
CREATE INDEX idx_report_access_audit_correlation_id ON public.report_access_audit USING btree (correlation_id);
CREATE INDEX idx_report_access_audit_lsp_created ON public.report_access_audit USING btree (lsp_id, created_at DESC);
CREATE INDEX idx_report_access_audit_report_request_id ON public.report_access_audit USING btree (report_request_id);
CREATE INDEX idx_report_request_created_at ON public.report_request USING btree (created_at);
CREATE INDEX idx_report_request_lsp ON public.report_request USING btree (lsp_id);
CREATE INDEX idx_report_request_status ON public.report_request USING btree (status);
CREATE INDEX idx_report_request_status_created_at ON public.report_request USING btree (status, created_at);
CREATE INDEX idx_webhook_event_delivery_attempt_outbox_created_at ON public.webhook_event_delivery_attempt USING btree (outbox_event_id, created_at DESC);
CREATE INDEX idx_webhook_event_outbox_created_at ON public.webhook_event_outbox USING btree (created_at DESC);
CREATE INDEX idx_webhook_event_outbox_dispatch ON public.webhook_event_outbox USING btree (status, next_attempt_at, created_at);
CREATE INDEX idx_webhook_event_outbox_loan_application_created_at ON public.webhook_event_outbox USING btree (loan_application_id, created_at DESC);
CREATE INDEX idx_webhook_event_outbox_lsp_created_at ON public.webhook_event_outbox USING btree (lsp_id, created_at DESC);
CREATE INDEX idx_webhook_event_outbox_stale_in_flight_claim ON public.webhook_event_outbox USING btree (claim_expires_at) WHERE ((status)::text = 'IN_FLIGHT'::text);
CREATE INDEX idx_webhook_outbox_redrive_audit_correlation_id ON public.webhook_outbox_redrive_audit USING btree (correlation_id);
CREATE INDEX idx_webhook_outbox_redrive_audit_event_created ON public.webhook_outbox_redrive_audit USING btree (webhook_event_id, created_at DESC);
CREATE INDEX idx_webhook_outbox_redrive_audit_lsp_created ON public.webhook_outbox_redrive_audit USING btree (lsp_id, created_at DESC);
CREATE INDEX ix_lsp_ip_allowlist_lsp ON public.lsp_api_ip_allowlist USING btree (lsp_id);
CREATE INDEX ix_lsp_ui_ip_allowlist_lsp ON public.lsp_ui_ip_allowlist USING btree (lsp_id);
CREATE UNIQUE INDEX uk_admin_api_idempotency_scope ON public.admin_api_idempotency_record USING btree (operation_key, idempotency_key);
CREATE UNIQUE INDEX uk_borrower_pan ON public.borrower USING btree (pan);
CREATE UNIQUE INDEX uk_lsp_api_idempotency_scope ON public.lsp_api_idempotency_record USING btree (lsp_id, operation_key, idempotency_key);
ALTER TABLE ONLY public.api_client_audit_event
    ADD CONSTRAINT api_client_audit_event_api_client_id_fkey FOREIGN KEY (api_client_id) REFERENCES public.api_client(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.api_client
    ADD CONSTRAINT api_client_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id) ON DELETE RESTRICT;
ALTER TABLE ONLY public.app_role_permission
    ADD CONSTRAINT app_role_permission_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES public.app_permission(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.app_role_permission
    ADD CONSTRAINT app_role_permission_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.app_role(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.app_user_audit_event
    ADD CONSTRAINT app_user_audit_event_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.app_user_role
    ADD CONSTRAINT app_user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.app_role(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.app_user_role
    ADD CONSTRAINT app_user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.auth_event_audit
    ADD CONSTRAINT auth_event_audit_api_client_id_fkey FOREIGN KEY (api_client_id) REFERENCES public.api_client(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.auth_event_audit
    ADD CONSTRAINT auth_event_audit_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.borrower_bank_details_update_audit
    ADD CONSTRAINT borrower_bank_details_update_audit_borrower_id_fkey FOREIGN KEY (borrower_id) REFERENCES public.borrower(id);
ALTER TABLE ONLY public.borrower_bank_details_update_audit
    ADD CONSTRAINT borrower_bank_details_update_audit_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.borrower_lsp_access
    ADD CONSTRAINT borrower_lsp_access_borrower_id_fkey FOREIGN KEY (borrower_id) REFERENCES public.borrower(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.borrower_lsp_access
    ADD CONSTRAINT borrower_lsp_access_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.borrower_pii_reveal_audit
    ADD CONSTRAINT borrower_pii_reveal_audit_borrower_id_fkey FOREIGN KEY (borrower_id) REFERENCES public.borrower(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.borrower_pii_reveal_audit
    ADD CONSTRAINT borrower_pii_reveal_audit_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.disbursement_outcome_audit
    ADD CONSTRAINT disbursement_outcome_audit_loan_account_id_fkey FOREIGN KEY (loan_account_id) REFERENCES public.loan_account(id);
ALTER TABLE ONLY public.disbursement_outcome_audit
    ADD CONSTRAINT disbursement_outcome_audit_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id);
ALTER TABLE ONLY public.loan_application_document_access_audit
    ADD CONSTRAINT fk_loan_document_access_audit_application FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id);
ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT fk_refresh_token_api_client FOREIGN KEY (api_client_id) REFERENCES public.api_client(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT fk_refresh_token_app_user FOREIGN KEY (app_user_id) REFERENCES public.app_user(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.webhook_event_outbox
    ADD CONSTRAINT fk_webhook_event_outbox_loan_application FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id) ON DELETE RESTRICT;
ALTER TABLE ONLY public.loan_account
    ADD CONSTRAINT loan_account_borrower_id_fkey FOREIGN KEY (borrower_id) REFERENCES public.borrower(id);
ALTER TABLE ONLY public.loan_account
    ADD CONSTRAINT loan_account_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id);
ALTER TABLE ONLY public.loan_account
    ADD CONSTRAINT loan_account_loan_product_id_fkey FOREIGN KEY (loan_product_id) REFERENCES public.loan_product(id);
ALTER TABLE ONLY public.loan_account
    ADD CONSTRAINT loan_account_loan_product_version_id_fkey FOREIGN KEY (loan_product_version_id) REFERENCES public.loan_product_version(id);
ALTER TABLE ONLY public.loan_account
    ADD CONSTRAINT loan_account_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.loan_application_assignment_event
    ADD CONSTRAINT loan_application_assignment_event_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_application_audit_event
    ADD CONSTRAINT loan_application_audit_event_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id);
ALTER TABLE ONLY public.loan_application
    ADD CONSTRAINT loan_application_borrower_id_fkey FOREIGN KEY (borrower_id) REFERENCES public.borrower(id) ON DELETE RESTRICT;
ALTER TABLE ONLY public.loan_application_document_access_audit_type
    ADD CONSTRAINT loan_application_document_access_audit_type_audit_id_fkey FOREIGN KEY (audit_id) REFERENCES public.loan_application_document_access_audit(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_application_document_checklist
    ADD CONSTRAINT loan_application_document_checklist_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_application_intake_audit
    ADD CONSTRAINT loan_application_intake_audit_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_application
    ADD CONSTRAINT loan_application_loan_product_id_fkey FOREIGN KEY (loan_product_id) REFERENCES public.loan_product(id) ON DELETE RESTRICT;
ALTER TABLE ONLY public.loan_application
    ADD CONSTRAINT loan_application_loan_product_version_id_fkey FOREIGN KEY (loan_product_version_id) REFERENCES public.loan_product_version(id);
ALTER TABLE ONLY public.loan_application
    ADD CONSTRAINT loan_application_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id) ON DELETE RESTRICT;
ALTER TABLE ONLY public.loan_application_pii_reveal_audit
    ADD CONSTRAINT loan_application_pii_reveal_audit_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id);
ALTER TABLE ONLY public.loan_application_pii_reveal_audit
    ADD CONSTRAINT loan_application_pii_reveal_audit_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.loan_application_status_transition
    ADD CONSTRAINT loan_application_status_transition_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_delinquency_state
    ADD CONSTRAINT loan_delinquency_state_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_disbursement_bank_mismatch_log
    ADD CONSTRAINT loan_disbursement_bank_mismatch_log_loan_application_id_fkey FOREIGN KEY (loan_application_id) REFERENCES public.loan_application(id);
ALTER TABLE ONLY public.loan_disbursement_bank_mismatch_log
    ADD CONSTRAINT loan_disbursement_bank_mismatch_log_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.loan_disbursement_request_log
    ADD CONSTRAINT loan_disbursement_request_log_loan_account_id_fkey FOREIGN KEY (loan_account_id) REFERENCES public.loan_account(id);
ALTER TABLE ONLY public.loan_foreclosure_quote
    ADD CONSTRAINT loan_foreclosure_quote_loan_account_id_fkey FOREIGN KEY (loan_account_id) REFERENCES public.loan_account(id);
ALTER TABLE ONLY public.loan_payment_transaction
    ADD CONSTRAINT loan_payment_transaction_loan_account_id_fkey FOREIGN KEY (loan_account_id) REFERENCES public.loan_account(id);
ALTER TABLE ONLY public.loan_payment_transaction
    ADD CONSTRAINT loan_payment_transaction_repayment_installment_id_fkey FOREIGN KEY (repayment_installment_id) REFERENCES public.loan_repayment_schedule_installment(id);
ALTER TABLE ONLY public.loan_product_audit_event
    ADD CONSTRAINT loan_product_audit_event_loan_product_id_fkey FOREIGN KEY (loan_product_id) REFERENCES public.loan_product(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_product_lsp_mapping
    ADD CONSTRAINT loan_product_lsp_mapping_loan_product_id_fkey FOREIGN KEY (loan_product_id) REFERENCES public.loan_product(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_product_lsp_mapping
    ADD CONSTRAINT loan_product_lsp_mapping_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_product_version
    ADD CONSTRAINT loan_product_version_loan_product_id_fkey FOREIGN KEY (loan_product_id) REFERENCES public.loan_product(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.loan_repayment_schedule_installment
    ADD CONSTRAINT loan_repayment_schedule_installment_loan_account_id_fkey FOREIGN KEY (loan_account_id) REFERENCES public.loan_account(id);
ALTER TABLE ONLY public.lsp_api_idempotency_record
    ADD CONSTRAINT lsp_api_idempotency_record_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.lsp_audit_event
    ADD CONSTRAINT lsp_audit_event_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.lsp_api_ip_allowlist
    ADD CONSTRAINT lsp_ip_allowlist_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.lsp_ui_ip_allowlist
    ADD CONSTRAINT lsp_ui_ip_allowlist_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.portfolio_kpi_snapshot
    ADD CONSTRAINT portfolio_kpi_snapshot_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.report_access_audit
    ADD CONSTRAINT report_access_audit_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.report_access_audit
    ADD CONSTRAINT report_access_audit_report_request_id_fkey FOREIGN KEY (report_request_id) REFERENCES public.report_request(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.report_request
    ADD CONSTRAINT report_request_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.webhook_event_delivery_attempt
    ADD CONSTRAINT webhook_event_delivery_attempt_outbox_event_id_fkey FOREIGN KEY (outbox_event_id) REFERENCES public.webhook_event_outbox(id);
ALTER TABLE ONLY public.webhook_event_outbox
    ADD CONSTRAINT webhook_event_outbox_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.webhook_outbox_redrive_audit
    ADD CONSTRAINT webhook_outbox_redrive_audit_lsp_id_fkey FOREIGN KEY (lsp_id) REFERENCES public.lsp(id);
ALTER TABLE ONLY public.webhook_outbox_redrive_audit
    ADD CONSTRAINT webhook_outbox_redrive_audit_webhook_event_id_fkey FOREIGN KEY (webhook_event_id) REFERENCES public.webhook_event_outbox(id);
ALTER TABLE public.api_client ENABLE ROW LEVEL SECURITY;
CREATE POLICY api_client_tenant_policy ON public.api_client TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
ALTER TABLE public.app_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY app_user_tenant_policy ON public.app_user TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
ALTER TABLE public.borrower ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.borrower_lsp_access ENABLE ROW LEVEL SECURITY;
CREATE POLICY borrower_lsp_access_tenant_policy ON public.borrower_lsp_access TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
CREATE POLICY borrower_tenant_delete_policy ON public.borrower FOR DELETE TO lms_tenant_app USING ((EXISTS ( SELECT 1
   FROM public.borrower_lsp_access access
  WHERE ((access.borrower_id = borrower.id) AND (access.lsp_id = public.app_current_lsp_id())))));
CREATE POLICY borrower_tenant_insert_policy ON public.borrower FOR INSERT TO lms_tenant_app WITH CHECK ((public.app_current_lsp_id() IS NOT NULL));
CREATE POLICY borrower_tenant_select_policy ON public.borrower FOR SELECT TO lms_tenant_app USING ((EXISTS ( SELECT 1
   FROM public.borrower_lsp_access access
  WHERE ((access.borrower_id = borrower.id) AND (access.lsp_id = public.app_current_lsp_id())))));
CREATE POLICY borrower_tenant_update_policy ON public.borrower FOR UPDATE TO lms_tenant_app USING ((EXISTS ( SELECT 1
   FROM public.borrower_lsp_access access
  WHERE ((access.borrower_id = borrower.id) AND (access.lsp_id = public.app_current_lsp_id()))))) WITH CHECK ((EXISTS ( SELECT 1
   FROM public.borrower_lsp_access access
  WHERE ((access.borrower_id = borrower.id) AND (access.lsp_id = public.app_current_lsp_id())))));
ALTER TABLE public.loan_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_account_tenant_policy ON public.loan_account TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
ALTER TABLE public.loan_application ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.loan_application_assignment_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_application_assignment_event_tenant_policy ON public.loan_application_assignment_event TO lms_tenant_app USING (public.tenant_owns_application(loan_application_id)) WITH CHECK (public.tenant_owns_application(loan_application_id));
ALTER TABLE public.loan_application_audit_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_application_audit_event_tenant_policy ON public.loan_application_audit_event TO lms_tenant_app USING (public.tenant_owns_application(loan_application_id)) WITH CHECK (public.tenant_owns_application(loan_application_id));
ALTER TABLE public.loan_application_document_access_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_application_document_access_audit_tenant_policy ON public.loan_application_document_access_audit TO lms_tenant_app USING (public.tenant_owns_application(loan_application_id)) WITH CHECK (public.tenant_owns_application(loan_application_id));
ALTER TABLE public.loan_application_document_access_audit_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.loan_application_document_checklist ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_application_document_checklist_tenant_policy ON public.loan_application_document_checklist TO lms_tenant_app USING (public.tenant_owns_application(loan_application_id)) WITH CHECK (public.tenant_owns_application(loan_application_id));
ALTER TABLE public.loan_application_intake_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_application_intake_audit_tenant_policy ON public.loan_application_intake_audit TO lms_tenant_app USING (public.tenant_owns_application(loan_application_id)) WITH CHECK (public.tenant_owns_application(loan_application_id));
ALTER TABLE public.loan_application_pii_reveal_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_application_pii_reveal_audit_tenant_policy ON public.loan_application_pii_reveal_audit TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
ALTER TABLE public.loan_application_status_transition ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_application_status_transition_tenant_policy ON public.loan_application_status_transition TO lms_tenant_app USING (public.tenant_owns_application(loan_application_id)) WITH CHECK (public.tenant_owns_application(loan_application_id));
CREATE POLICY loan_application_tenant_policy ON public.loan_application TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
ALTER TABLE public.loan_disbursement_request_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_disbursement_request_log_tenant_policy ON public.loan_disbursement_request_log TO lms_tenant_app USING (public.tenant_owns_loan_account(loan_account_id)) WITH CHECK (public.tenant_owns_loan_account(loan_account_id));
CREATE POLICY loan_document_access_audit_type_tenant_policy ON public.loan_application_document_access_audit_type TO lms_tenant_app USING ((EXISTS ( SELECT 1
   FROM public.loan_application_document_access_audit audit
  WHERE ((audit.id = loan_application_document_access_audit_type.audit_id) AND public.tenant_owns_application(audit.loan_application_id))))) WITH CHECK ((EXISTS ( SELECT 1
   FROM public.loan_application_document_access_audit audit
  WHERE ((audit.id = loan_application_document_access_audit_type.audit_id) AND public.tenant_owns_application(audit.loan_application_id)))));
ALTER TABLE public.loan_foreclosure_quote ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_foreclosure_quote_tenant_policy ON public.loan_foreclosure_quote TO lms_tenant_app USING (public.tenant_owns_loan_account(loan_account_id)) WITH CHECK (public.tenant_owns_loan_account(loan_account_id));
ALTER TABLE public.loan_payment_transaction ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_payment_transaction_tenant_policy ON public.loan_payment_transaction TO lms_tenant_app USING (public.tenant_owns_loan_account(loan_account_id)) WITH CHECK (public.tenant_owns_loan_account(loan_account_id));
ALTER TABLE public.loan_product_lsp_mapping ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_product_lsp_mapping_tenant_policy ON public.loan_product_lsp_mapping TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
ALTER TABLE public.loan_repayment_schedule_installment ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_repayment_schedule_installment_tenant_policy ON public.loan_repayment_schedule_installment TO lms_tenant_app USING (public.tenant_owns_loan_account(loan_account_id)) WITH CHECK (public.tenant_owns_loan_account(loan_account_id));
ALTER TABLE public.lsp_api_idempotency_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY lsp_api_idempotency_record_tenant_policy ON public.lsp_api_idempotency_record TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
ALTER TABLE public.report_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY report_request_tenant_policy ON public.report_request TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
ALTER TABLE public.webhook_event_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY webhook_event_outbox_tenant_policy ON public.webhook_event_outbox TO lms_tenant_app USING ((lsp_id = public.app_current_lsp_id())) WITH CHECK ((lsp_id = public.app_current_lsp_id()));
