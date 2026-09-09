-- H02 — immutable disbursement observation trail + explicit bounded reconciliation queue.
--
-- Additive only (V122/V123 reserved for C06 follow-ups, V124 reserved for H15):
-- no change to disbursement_intent, loan_disbursement_request_log or loan_account.
-- The mutable request log stays the compatibility state; disbursement_observation is the
-- separate canonical evidence (one immutable row per attempted initiate/poll, including
-- timeouts/duplicates/stale responses).
--
-- Backfill discipline: exactly one LEGACY observation per loan account from the actual
-- latest stored request-log row — history columns stay NULL when the stored evidence lacks
-- them. No LEGACY- references are invented, no UNKNOWN beneficiary/mode placeholders are
-- written, and the V111 backfilled intent (itself synthesized from the live borrower row)
-- is never used as historical evidence: only the intent id is kept as a call
-- identity/evidence association. Missing evidence routes to the operator queue, never to
-- fabricated history. Stored provider_status values are mapped truthfully
-- (DISBURSED/SUCCESS → SUCCESS, FAILED → FAILED, everything else → PENDING unresolved);
-- only actually-definitive backfilled evidence can feed evidence-backed manual resolution.

CREATE TABLE disbursement_observation (
    id UUID PRIMARY KEY,
    loan_account_id UUID NOT NULL REFERENCES loan_account (id),
    -- Call identity/evidence association. Test databases truncate this table before
    -- fixture-shaping intent deletes (see IntegrationTestDatabaseCleaner and the C06 test
    -- cleanup); production never deletes intent rows.
    intent_id UUID REFERENCES disbursement_intent (id),
    -- NULL means the stored evidence carries no reference: operator-only, never invented.
    tran_ref_no VARCHAR(64),
    attempt INTEGER NOT NULL DEFAULT 0,
    -- Durable per-call identity for polls: the pre-network poll sequence assigned by the
    -- claim step, carried into the immutable result (including timeouts). NULL for INITIATE
    -- and LEGACY rows. A claimed sequence with no result row is a crashed attempt that stays
    -- visible as attempted-with-missing-result; responses are never fabricated for it.
    poll_seq INTEGER,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('INITIATE', 'POLL', 'LEGACY')),
    -- Timeouts and not-yet-queryable status checks are PENDING with query_resolved=false;
    -- the disposition enum stays the provider contract (SUCCESS/FAILED/PENDING).
    disposition VARCHAR(16) NOT NULL CHECK (disposition IN ('SUCCESS', 'FAILED', 'PENDING')),
    query_resolved BOOLEAN NOT NULL DEFAULT TRUE,
    is_duplicate BOOLEAN NOT NULL DEFAULT FALSE,
    provider_name VARCHAR(64) NOT NULL,
    provider_request_id VARCHAR(128),
    act_code VARCHAR(16),
    bank_rrn VARCHAR(32),
    decline_kind VARCHAR(16),
    -- NULL means not stored: never backfilled from the borrower row or the V111 intent.
    beneficiary_ifsc VARCHAR(16),
    beneficiary_account_number VARCHAR(64),
    payment_mode VARCHAR(16),
    request_payload_json JSONB NOT NULL,
    response_payload_json JSONB NOT NULL,
    correlation_id VARCHAR(128),
    provenance VARCHAR(16) NOT NULL DEFAULT 'LIVE' CHECK (provenance IN ('LIVE', 'LEGACY')),
    created_by VARCHAR(255) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_disbursement_observation_account_time
    ON disbursement_observation (loan_account_id, observed_at DESC);
CREATE INDEX idx_disbursement_observation_tran_ref
    ON disbursement_observation (tran_ref_no, observed_at DESC);
CREATE INDEX idx_disbursement_observation_intent
    ON disbursement_observation (intent_id, observed_at DESC)
    WHERE intent_id IS NOT NULL;
CREATE INDEX idx_disbursement_observation_disposition
    ON disbursement_observation (disposition, query_resolved)
    WHERE query_resolved AND NOT is_duplicate;

-- Explicit bounded unresolved queue: one row per loan account with live/uncertain money.
-- Covers UNKNOWN/REQUESTED/parked accounts plus legacy mismatches, stranded terminals and
-- conflicting definitive evidence. tran_ref_no stays NULL when no reference was ever stored
-- so no-reference accounts remain visible to operators. next_poll_at/backoff bounds provider
-- load; first_seen_at (earliest original evidence, never rewritten by retries) drives age
-- escalation; owner carries operator ownership once claimed.
CREATE TABLE disbursement_reconciliation_queue (
    loan_account_id UUID PRIMARY KEY REFERENCES loan_account (id) ON DELETE CASCADE,
    -- Same association discipline as disbursement_observation.intent_id above.
    intent_id UUID REFERENCES disbursement_intent (id),
    tran_ref_no VARCHAR(64),
    reason VARCHAR(32) NOT NULL CHECK (reason IN (
        'UNKNOWN', 'REQUESTED', 'PARKED',
        'LEGACY_MISMATCH', 'STRANDED_TERMINAL', 'CONFLICTING_EVIDENCE'
    )),
    next_poll_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    poll_count INTEGER NOT NULL DEFAULT 0,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_observation_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    owner VARCHAR(128),
    escalated BOOLEAN NOT NULL DEFAULT FALSE,
    details TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_disbursement_recon_next_poll
    ON disbursement_reconciliation_queue (next_poll_at ASC);
CREATE INDEX idx_disbursement_recon_reason
    ON disbursement_reconciliation_queue (reason, first_seen_at ASC);

-- H02-BACKFILL-OBSERVATION-START
-- Legacy backfill: one observation per loan account from the actual latest stored
-- request-log row only, extracted with ->> from the stored payload. Missing history stays
-- NULL; the live intent contributes only its id (call association), never its beneficiary
-- fields. Payload JSON is copied byte-for-byte; no borrower data is consulted.
INSERT INTO disbursement_observation (
    id,
    loan_account_id,
    intent_id,
    tran_ref_no,
    attempt,
    kind,
    disposition,
    query_resolved,
    is_duplicate,
    provider_name,
    provider_request_id,
    act_code,
    bank_rrn,
    decline_kind,
    beneficiary_ifsc,
    beneficiary_account_number,
    payment_mode,
    request_payload_json,
    response_payload_json,
    correlation_id,
    provenance,
    created_by,
    observed_at
)
SELECT
    gen_random_uuid(),
    log.loan_account_id,
    live_intent.id,
    NULLIF(log.tran_ref_no, ''),
    0,
    'LEGACY',
    CASE
        WHEN log.provider_status IN ('DISBURSED', 'SUCCESS') THEN 'SUCCESS'
        WHEN log.provider_status = 'FAILED' THEN 'FAILED'
        ELSE 'PENDING'
    END,
    log.provider_status IN ('DISBURSED', 'SUCCESS', 'FAILED'),
    FALSE,
    log.provider_name,
    log.provider_request_id,
    log.provider_act_code,
    log.bank_rrn,
    log.decline_kind,
    NULLIF(log.request_payload_json ->> 'beneficiaryIfsc', ''),
    NULLIF(log.request_payload_json ->> 'beneficiaryAccountNumber', ''),
    NULLIF(log.payment_mode, ''),
    log.request_payload_json,
    log.response_payload_json,
    log.correlation_id,
    'LEGACY',
    log.actor_username,
    log.updated_at
FROM loan_disbursement_request_log log
LEFT JOIN LATERAL (
    SELECT di.id
    FROM disbursement_intent di
    WHERE di.loan_account_id = log.loan_account_id
      AND di.state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
    LIMIT 1
) live_intent ON TRUE
WHERE log.id IN (
    SELECT DISTINCT ON (l2.loan_account_id) l2.id
    FROM loan_disbursement_request_log l2
    ORDER BY l2.loan_account_id, l2.created_at DESC
)
ON CONFLICT DO NOTHING;
-- H02-BACKFILL-OBSERVATION-END

-- H02-BACKFILL-QUEUE-START
-- Queue backfill: every account with uncertain money becomes visible to operators, including
-- accounts whose stored evidence carries no reference at all. Stranded terminals are queued
-- first so the C02 repair path owns them; the in-flight sweep then skips already-queued
-- accounts. first_seen_at is the earliest original request/intent stamp per account.
-- (b) Stranded terminals: terminal intent whose loan still reads REQUESTED (C02 repair path).
INSERT INTO disbursement_reconciliation_queue (
    loan_account_id, intent_id, tran_ref_no, reason, next_poll_at,
    poll_count, first_seen_at, last_observation_at, details
)
SELECT
    la.id,
    di.id,
    di.tran_ref_no,
    'STRANDED_TERMINAL',
    NOW(),
    0,
    COALESCE(stamps.first_seen, di.created_at, NOW()),
    COALESCE(stamps.last_seen, di.updated_at, NOW()),
    'H02 V121 backfill: terminal intent result not yet applied to the loan; repair from stored evidence without re-initiation.'
FROM loan_account la
JOIN disbursement_intent di ON di.loan_account_id = la.id
LEFT JOIN LATERAL (
    SELECT MIN(t.stamp) AS first_seen, MAX(t.stamp) AS last_seen
    FROM (
        SELECT l2.created_at AS stamp
        FROM loan_disbursement_request_log l2
        WHERE l2.loan_account_id = la.id
        UNION ALL
        SELECT di2.created_at AS stamp
        FROM disbursement_intent di2
        WHERE di2.loan_account_id = la.id
    ) t
) stamps ON TRUE
WHERE la.status = 'DISBURSEMENT_REQUESTED'
  AND di.state IN ('SUCCEEDED', 'FAILED')
ON CONFLICT (loan_account_id) DO NOTHING;

-- (a) In-flight or parked accounts keep polling their original reference. A NULL surviving
-- reference, or any payload/column/live-intent contradiction, queues as LEGACY_MISMATCH for
-- operator-only resolution — history is never fabricated to clear it.
INSERT INTO disbursement_reconciliation_queue (
    loan_account_id, intent_id, tran_ref_no, reason, next_poll_at,
    poll_count, first_seen_at, last_observation_at, details
)
SELECT
    la.id,
    live_intent.id,
    COALESCE(live_intent.tran_ref_no, latest_log.tran_ref_no),
    CASE
        WHEN COALESCE(live_intent.tran_ref_no, latest_log.tran_ref_no) IS NULL THEN 'LEGACY_MISMATCH'
        WHEN la.status = 'DISBURSEMENT_PENDING_RECONCILIATION' THEN 'PARKED'
        WHEN live_intent.state = 'UNKNOWN' THEN 'UNKNOWN'
        WHEN live_intent.id IS NOT NULL
            AND live_intent.tran_ref_no IS DISTINCT FROM latest_log.tran_ref_no THEN 'LEGACY_MISMATCH'
        WHEN (latest_log.request_payload_json ->> 'tranRefNo') IS DISTINCT FROM latest_log.tran_ref_no
            THEN 'LEGACY_MISMATCH'
        ELSE 'REQUESTED'
    END,
    NOW(),
    0,
    COALESCE(stamps.first_seen, live_intent.created_at, latest_log.created_at, NOW()),
    COALESCE(stamps.last_seen, latest_log.updated_at, live_intent.updated_at, NOW()),
    'H02 V121 backfill: unresolved money carried forward; poll the original reference, never re-initiate.'
FROM loan_account la
LEFT JOIN LATERAL (
    SELECT di.id, di.tran_ref_no, di.state, di.created_at, di.updated_at
    FROM disbursement_intent di
    WHERE di.loan_account_id = la.id
      AND di.state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
    LIMIT 1
) live_intent ON TRUE
LEFT JOIN LATERAL (
    SELECT l2.tran_ref_no, l2.created_at, l2.updated_at, l2.request_payload_json
    FROM loan_disbursement_request_log l2
    WHERE l2.loan_account_id = la.id
    ORDER BY l2.created_at DESC
    LIMIT 1
) latest_log ON TRUE
LEFT JOIN LATERAL (
    SELECT MIN(t.stamp) AS first_seen, MAX(t.stamp) AS last_seen
    FROM (
        SELECT l3.created_at AS stamp
        FROM loan_disbursement_request_log l3
        WHERE l3.loan_account_id = la.id
        UNION ALL
        SELECT di3.created_at AS stamp
        FROM disbursement_intent di3
        WHERE di3.loan_account_id = la.id
    ) t
) stamps ON TRUE
WHERE la.status IN ('DISBURSEMENT_REQUESTED', 'DISBURSEMENT_PENDING_RECONCILIATION')
ON CONFLICT (loan_account_id) DO NOTHING;
-- H02-BACKFILL-QUEUE-END

-- Immutability: observations are append-only canonical evidence (loan_event pattern).
-- UPDATE/DELETE are rejected in production. Integration-test cleanup runs through
-- IntegrationTestDatabaseCleaner which TRUNCATEs this table (TRUNCATE does not fire
-- row-level triggers), so the ephemeral-database guard still applies and no repo delete
-- path can silently drop evidence.
CREATE OR REPLACE FUNCTION reject_disbursement_observation_mutation() RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'disbursement_observation is append-only; updates and deletes are forbidden'
        USING ERRCODE = '55000';
END
$$;

DROP TRIGGER IF EXISTS disbursement_observation_append_only ON disbursement_observation;
CREATE TRIGGER disbursement_observation_append_only
    BEFORE UPDATE OR DELETE ON disbursement_observation
    FOR EACH ROW
    EXECUTE FUNCTION reject_disbursement_observation_mutation();

-- RLS: observations are insert/select-only for the tenant role (no UPDATE/DELETE grant,
-- matching the trigger); the queue is fully tenant-managed via the owning loan account.
DO $$
BEGIN
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE disbursement_observation TO %I',
        '${tenant_app_role}'
    );
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE disbursement_reconciliation_queue TO %I',
        '${tenant_app_role}'
    );
END
$$;

ALTER TABLE disbursement_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE disbursement_observation FORCE ROW LEVEL SECURITY;
ALTER TABLE disbursement_reconciliation_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE disbursement_reconciliation_queue FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS disbursement_observation_tenant_policy ON disbursement_observation;
CREATE POLICY disbursement_observation_tenant_policy ON disbursement_observation
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_loan_account(loan_account_id))
    WITH CHECK (tenant_owns_loan_account(loan_account_id));

DROP POLICY IF EXISTS disbursement_reconciliation_queue_tenant_policy ON disbursement_reconciliation_queue;
CREATE POLICY disbursement_reconciliation_queue_tenant_policy ON disbursement_reconciliation_queue
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_loan_account(loan_account_id))
    WITH CHECK (tenant_owns_loan_account(loan_account_id));
