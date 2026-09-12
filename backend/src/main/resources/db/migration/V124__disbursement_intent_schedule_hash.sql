-- Freeze the canonical repayment-schedule hash on the disbursement intent.
--
-- The hash is written by intent creation for new rows only. Existing rows keep NULL:
-- legacy evidence must never be invented. A CREATED intent without frozen evidence must
-- not be submitted; an already-submitted instruction stays reconcilable through the
-- existing terminal-outcome/reconciliation paths. Nullable with no backfill, no default, and no RLS change
-- (the V120 table policy already covers every column).
ALTER TABLE disbursement_intent ADD COLUMN schedule_hash VARCHAR(64);

COMMENT ON COLUMN disbursement_intent.schedule_hash IS
    'Canonical SHA-256 hash of the persisted repayment schedule frozen at intent creation; NULL for rows created before the schedule-hash freeze.';
