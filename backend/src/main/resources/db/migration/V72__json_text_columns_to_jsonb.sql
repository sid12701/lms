-- F-10: Store JSON payload columns as real Postgres jsonb values.
--
-- These columns are written by application code as JSON object payloads. Moving
-- them from TEXT/VARCHAR to jsonb makes Postgres validate the JSON at write time
-- and allows future JSON path predicates/indexes without another type migration.

ALTER TABLE webhook_event_outbox
    ALTER COLUMN payload_json TYPE jsonb USING payload_json::jsonb,
    ADD CONSTRAINT chk_webhook_event_outbox_payload_json_object
        CHECK (jsonb_typeof(payload_json) = 'object');

ALTER TABLE loan_disbursement_request_log
    ALTER COLUMN request_payload_json TYPE jsonb USING request_payload_json::jsonb,
    ALTER COLUMN response_payload_json TYPE jsonb USING response_payload_json::jsonb,
    ADD CONSTRAINT chk_disbursement_request_payload_json_object
        CHECK (jsonb_typeof(request_payload_json) = 'object'),
    ADD CONSTRAINT chk_disbursement_response_payload_json_object
        CHECK (jsonb_typeof(response_payload_json) = 'object');

ALTER TABLE loan_application_status_transition
    ALTER COLUMN rejection_reason_json TYPE jsonb USING rejection_reason_json::jsonb,
    ADD CONSTRAINT chk_status_transition_rejection_reason_json_object
        CHECK (rejection_reason_json IS NULL OR jsonb_typeof(rejection_reason_json) = 'object');

ALTER TABLE ops_alert
    ALTER COLUMN context_json TYPE jsonb USING context_json::jsonb,
    ADD CONSTRAINT chk_ops_alert_context_json_object
        CHECK (context_json IS NULL OR jsonb_typeof(context_json) = 'object');

ALTER TABLE app_user_audit_event
    ALTER COLUMN before_state_json TYPE jsonb USING before_state_json::jsonb,
    ALTER COLUMN after_state_json TYPE jsonb USING after_state_json::jsonb,
    ADD CONSTRAINT chk_app_user_audit_before_state_json_object
        CHECK (jsonb_typeof(before_state_json) = 'object'),
    ADD CONSTRAINT chk_app_user_audit_after_state_json_object
        CHECK (jsonb_typeof(after_state_json) = 'object');

ALTER TABLE api_client_audit_event
    ALTER COLUMN details_json TYPE jsonb USING details_json::jsonb,
    ADD CONSTRAINT chk_api_client_audit_details_json_object
        CHECK (jsonb_typeof(details_json) = 'object');

ALTER TABLE loan_application_intake_audit
    ALTER COLUMN payload_json TYPE jsonb USING payload_json::jsonb,
    ADD CONSTRAINT chk_loan_application_intake_audit_payload_json_object
        CHECK (jsonb_typeof(payload_json) = 'object');
