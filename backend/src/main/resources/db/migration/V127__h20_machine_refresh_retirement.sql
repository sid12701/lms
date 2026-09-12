-- Controlled retirement of API_CLIENT refresh rows (audit rows retained).
UPDATE refresh_token
SET revoked = true,
    revoked_at = COALESCE(revoked_at, NOW())
WHERE auth_type = 'API_CLIENT'
  AND revoked = false;

-- External (Entra) credential invalidation epoch — compared to token iat, not injected tv.
ALTER TABLE api_client
    ADD COLUMN credentials_invalidated_at TIMESTAMPTZ;

COMMENT ON COLUMN api_client.credentials_invalidated_at IS
    'External machine tokens with iat before this instant are rejected. Set on revoke-all and deactivation.';
