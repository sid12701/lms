-- M08: alert deduplication is a canonical key enforced at the database, not a
-- check-then-insert race. Two partial unique indexes cover both dedupe branches and only
-- while the alert is NEW, so recurrence after acknowledgement stays possible:
--   (type, subject_type, subject_id)  — subject alerts (subject_type is part of the key:
--                                        different subject types must not conflate)
--   (type, correlation_id)            — subjectless alerts (rate limit, oldest transaction)

-- Preserve history instead of deleting duplicates: the earliest NEW row per key stays
-- active; every later duplicate is acknowledged with a link to the row that superseded it.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY type, subject_type, subject_id ORDER BY created_at ASC, id ASC
           ) AS rn,
           FIRST_VALUE(id) OVER (
               PARTITION BY type, subject_type, subject_id ORDER BY created_at ASC, id ASC
           ) AS keep_id
    FROM ops_alert
    WHERE status = 'NEW' AND subject_id IS NOT NULL
)
UPDATE ops_alert a
SET status = 'ACKNOWLEDGED',
    acknowledged_at = now(),
    acknowledged_by_username = 'system-migration',
    acknowledgement_note = 'Superseded by active alert ' || r.keep_id || ' during M08 dedupe.'
FROM ranked r
WHERE a.id = r.id AND r.rn > 1;

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY type, correlation_id ORDER BY created_at ASC, id ASC
           ) AS rn,
           FIRST_VALUE(id) OVER (
               PARTITION BY type, correlation_id ORDER BY created_at ASC, id ASC
           ) AS keep_id
    FROM ops_alert
    WHERE status = 'NEW' AND subject_id IS NULL AND correlation_id IS NOT NULL
)
UPDATE ops_alert a
SET status = 'ACKNOWLEDGED',
    acknowledged_at = now(),
    acknowledged_by_username = 'system-migration',
    acknowledgement_note = 'Superseded by active alert ' || r.keep_id || ' during M08 dedupe.'
FROM ranked r
WHERE a.id = r.id AND r.rn > 1;

-- NULLS NOT DISTINCT: a null subject_type forms a single dedupe key per (type, subject_id)
-- rather than letting every null row count as distinct.
CREATE UNIQUE INDEX uk_ops_alert_active_subject
    ON ops_alert (type, subject_type, subject_id) NULLS NOT DISTINCT
    WHERE status = 'NEW' AND subject_id IS NOT NULL;

CREATE UNIQUE INDEX uk_ops_alert_active_correlation
    ON ops_alert (type, correlation_id)
    WHERE status = 'NEW' AND subject_id IS NULL AND correlation_id IS NOT NULL;
