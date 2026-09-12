-- Human session families with sid-bound access checks, atomic refresh lineage,
-- legacy forced reauth and key-policy epoch cutover.
--
-- New small auth_session family table (one row per login session). Human refresh rows
-- attach via family_id; machine (API_CLIENT) refresh rows never get a family.
-- Legacy rows keep NULL family and are forced to reauth (no backfill, no default).
-- Policy epoch binds new sessions to the current issuer/audience/signing policy so a
-- key change cannot refresh into the new policy without reauth.
CREATE TABLE auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    policy_epoch VARCHAR(128) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_auth_session_user ON auth_session(user_id);
CREATE INDEX idx_auth_session_user_revoked ON auth_session(user_id, revoked);

ALTER TABLE refresh_token ADD COLUMN family_id UUID NULL REFERENCES auth_session(id) ON DELETE CASCADE;
ALTER TABLE refresh_token ADD COLUMN replaced_by_hash VARCHAR(64) NULL;
ALTER TABLE refresh_token ADD COLUMN revoked_at TIMESTAMPTZ NULL;
ALTER TABLE refresh_token ADD COLUMN issued_tv BIGINT NULL;
ALTER TABLE refresh_token ADD COLUMN issued_pwdv_millis BIGINT NULL;
ALTER TABLE refresh_token ADD COLUMN issued_policy_epoch VARCHAR(128) NULL;

CREATE INDEX idx_refresh_token_family ON refresh_token(family_id);
CREATE INDEX idx_refresh_token_family_revoked ON refresh_token(family_id, revoked);

-- Subject integrity reuses the V73 XOR check (auth_type=PASSWORD exactly one app_user,
-- auth_type=API_CLIENT exactly one api_client); no duplicate constraint is added here.

COMMENT ON TABLE auth_session IS
    'One row per human login session (family). Access JWT sid binds to this id; family revoke kills only this family.';
COMMENT ON COLUMN auth_session.policy_epoch IS
    'Nonsecret hash of issuer/audiences/signing policy at issuance; refresh rejects on epoch mismatch (forced reauth).';
COMMENT ON COLUMN refresh_token.family_id IS
    'Owning session family for human rows; NULL for machine rows and legacy human rows created before the session-family model (forced reauth, never backfilled).';
COMMENT ON COLUMN refresh_token.replaced_by_hash IS
    'Successor hash set on the consumed parent in the same rotation TX; identifies the benign direct-parent loser.';
COMMENT ON COLUMN refresh_token.issued_tv IS
    'User tokenVersion copied at issuance; rotation validates live tv against the presented row.';
COMMENT ON COLUMN refresh_token.issued_pwdv_millis IS
    'User passwordChangedAt millis copied at issuance; rotation validates live pwdv against the presented row.';
COMMENT ON COLUMN refresh_token.issued_policy_epoch IS
    'Policy epoch copied at issuance; rotation rejects when the current epoch differs (key/audience cutover).';

-- Global auth tables: no tenant RLS (consistent with refresh_token/app_user global scope).
-- Cascade: deleting app_user cascades to auth_session and refresh_token; deleting a
-- session cascades to its family refresh rows. Revoked-token lineage is retained for the
-- required reuse window; no purge in this migration.
