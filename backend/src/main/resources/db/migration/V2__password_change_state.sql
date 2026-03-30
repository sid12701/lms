ALTER TABLE app_user
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE app_user
    ADD COLUMN password_changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
