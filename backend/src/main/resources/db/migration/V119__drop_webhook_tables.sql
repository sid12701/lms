-- ADR 0007: partner lifecycle updates are pull-based. The webhook delivery machinery
-- was deleted in the previous ticket; this removes its remaining trace from the schema.
-- Pre-production greenfield: a hard destructive cutover, no expand--contract sequence.
--
-- Dropping each table takes its indexes, check constraints, foreign keys, row-level
-- security policy and tenant-role grants with it, so V37, V41, V72 and V106's
-- webhook-specific statements are superseded rather than reverted.

DROP TABLE IF EXISTS webhook_event_delivery_attempt;
DROP TABLE IF EXISTS webhook_outbox_redrive_audit;
DROP TABLE IF EXISTS webhook_event_outbox;

-- V23's subscription columns. Nothing has read them since the LSP entity lost the
-- fields; V99 only rewrote their values.
ALTER TABLE lsp
    DROP COLUMN IF EXISTS webhook_enabled,
    DROP COLUMN IF EXISTS webhook_endpoint_url,
    DROP COLUMN IF EXISTS webhook_signing_secret,
    DROP COLUMN IF EXISTS webhook_event_types;

-- The dead-letter rule describes a failure mode that no longer exists: nothing
-- produces dead letters under the pull design. Seeded by V60 (id ...0605).
DELETE FROM ops_alert WHERE type = 'WEBHOOK_DEAD_LETTER';
DELETE FROM alert_rule WHERE code = 'WEBHOOK_DEAD_LETTER';
