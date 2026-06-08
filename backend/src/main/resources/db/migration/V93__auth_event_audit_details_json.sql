ALTER TABLE auth_event_audit
    ADD COLUMN IF NOT EXISTS details_json JSONB;
