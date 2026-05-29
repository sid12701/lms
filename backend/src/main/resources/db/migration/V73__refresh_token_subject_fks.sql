-- F-19: Replace refresh_token.username (free-text) with proper FKs to the
-- authenticated subject. Refresh tokens are issued for two kinds of subject:
--   - human users (auth_type = 'PASSWORD')  -> app_user
--   - API clients (auth_type = 'API_CLIENT') -> api_client
-- A single column cannot FK to two tables, so we add two nullable FKs with an
-- XOR CHECK that ties exactly one of them to the row's auth_type.

ALTER TABLE refresh_token
    ADD COLUMN app_user_id   UUID,
    ADD COLUMN api_client_id UUID,
    ADD CONSTRAINT fk_refresh_token_app_user
        FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_refresh_token_api_client
        FOREIGN KEY (api_client_id) REFERENCES api_client(id) ON DELETE CASCADE;

-- Backfill PASSWORD rows. app_user.username is canonical lowercase (V67); apply
-- LOWER() defensively in case any pre-V67 refresh_token rows retain mixed case.
UPDATE refresh_token rt
   SET app_user_id = u.id
  FROM app_user u
 WHERE rt.auth_type = 'PASSWORD'
   AND LOWER(rt.username) = u.username;

-- Backfill API_CLIENT rows. api_client.client_id is stored case-preserving;
-- ApiClientAuthenticationService only trims input, so exact match is correct.
UPDATE refresh_token rt
   SET api_client_id = c.id
  FROM api_client c
 WHERE rt.auth_type = 'API_CLIENT'
   AND rt.username = c.client_id;

-- Orphans: refresh tokens whose subject no longer exists (deleted user/client,
-- bootstrap admin without a seeded app_user row, etc.). They are already
-- unusable; refresh-token expiry would have removed them anyway.
DELETE FROM refresh_token
 WHERE app_user_id IS NULL AND api_client_id IS NULL;

-- Enforce subject-type alignment going forward.
ALTER TABLE refresh_token
    ADD CONSTRAINT chk_refresh_token_subject_xor CHECK (
        (auth_type = 'PASSWORD'   AND app_user_id   IS NOT NULL AND api_client_id IS NULL) OR
        (auth_type = 'API_CLIENT' AND api_client_id IS NOT NULL AND app_user_id   IS NULL)
    );

DROP INDEX IF EXISTS idx_refresh_token_username;
ALTER TABLE refresh_token DROP COLUMN username;

CREATE INDEX idx_refresh_token_app_user_id   ON refresh_token(app_user_id)   WHERE app_user_id   IS NOT NULL;
CREATE INDEX idx_refresh_token_api_client_id ON refresh_token(api_client_id) WHERE api_client_id IS NOT NULL;
