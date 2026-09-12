-- Neutralize audit-trace prefixes in catalog comments and backfilled queue details.
-- No schema or behavior change: rewords human-readable descriptions only.

COMMENT ON COLUMN disbursement_intent.schedule_hash IS
    'Canonical SHA-256 hash of the persisted repayment schedule frozen at intent creation; NULL for rows created before the schedule-hash freeze.';

UPDATE disbursement_reconciliation_queue
SET details = 'Backfill: terminal intent result not yet applied to the loan; repair from stored evidence without re-initiation.'
WHERE details = 'H02 V121 backfill: terminal intent result not yet applied to the loan; repair from stored evidence without re-initiation.';

UPDATE disbursement_reconciliation_queue
SET details = 'Backfill: unresolved money carried forward; poll the original reference, never re-initiate.'
WHERE details = 'H02 V121 backfill: unresolved money carried forward; poll the original reference, never re-initiate.';
