CREATE TABLE refresh_token (
    id          UUID PRIMARY KEY,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    username    VARCHAR(255) NOT NULL,
    auth_type   VARCHAR(32)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_token_username ON refresh_token(username);
CREATE INDEX idx_refresh_token_expires ON refresh_token(expires_at);
