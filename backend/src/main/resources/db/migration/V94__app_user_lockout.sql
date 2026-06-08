ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS locked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lock_reason VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_auth_event_audit_login_failed_username_ip_created
    ON auth_event_audit (username, actor_ip, created_at DESC)
    WHERE event_type = 'LOGIN_FAILED';
