-- M08 follow-up: the V133 dedupe keys constrained every NEW alert, including the
-- always-create paths (LSP bound violations, manual escalations, onboarding conflicts)
-- that intentionally file a fresh alert per occurrence. The fence now applies only to
-- rows written through the dedupe path: dedupe_protected marks them, and both partial
-- unique indexes restrict their predicate to protected rows. A repeated occurrence alert
-- is unprotected, never enters either index, and can never conflict with it.
--
-- Existing NEW rows are marked protected: they were all written before this distinction
-- existed, and treating them as dedupe-protected preserves V133's semantics for them.

DROP INDEX IF EXISTS uk_ops_alert_active_subject;
DROP INDEX IF EXISTS uk_ops_alert_active_correlation;

ALTER TABLE ops_alert
    ADD COLUMN dedupe_protected boolean NOT NULL DEFAULT false;

UPDATE ops_alert SET dedupe_protected = true WHERE status = 'NEW';

CREATE UNIQUE INDEX uk_ops_alert_active_subject
    ON ops_alert (type, subject_type, subject_id) NULLS NOT DISTINCT
    WHERE status = 'NEW' AND subject_id IS NOT NULL AND dedupe_protected;

CREATE UNIQUE INDEX uk_ops_alert_active_correlation
    ON ops_alert (type, correlation_id)
    WHERE status = 'NEW' AND subject_id IS NULL AND correlation_id IS NOT NULL AND dedupe_protected;
