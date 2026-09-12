ALTER TABLE app_user
    ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN failed_login_window_started_at TIMESTAMPTZ;
