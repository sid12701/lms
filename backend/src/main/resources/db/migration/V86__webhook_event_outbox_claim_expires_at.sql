-- Supabase applied a different script at V80; webhook claim expiry ships here.
ALTER TABLE webhook_event_outbox
    ADD COLUMN IF NOT EXISTS claim_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_webhook_event_outbox_stale_in_flight_claim
    ON webhook_event_outbox (claim_expires_at)
    WHERE status = 'IN_FLIGHT';
