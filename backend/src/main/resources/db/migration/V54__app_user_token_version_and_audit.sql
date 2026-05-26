ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0;

UPDATE app_user
SET token_version = 0
WHERE token_version IS NULL;

CREATE TABLE IF NOT EXISTS app_user_audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    actor_username VARCHAR(255) NOT NULL,
    before_state_json TEXT NOT NULL,
    after_state_json TEXT NOT NULL,
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_app_user_audit_event_user_created
    ON app_user_audit_event (user_id, created_at DESC);
