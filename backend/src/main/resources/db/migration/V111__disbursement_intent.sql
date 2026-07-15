CREATE TABLE disbursement_intent (
    id UUID PRIMARY KEY,
    loan_account_id UUID NOT NULL REFERENCES loan_account (id),
    tran_ref_no VARCHAR(64) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    payment_mode VARCHAR(16) NOT NULL,
    beneficiary_name VARCHAR(255) NOT NULL,
    beneficiary_account_number VARCHAR(64) NOT NULL,
    beneficiary_ifsc VARCHAR(16) NOT NULL,
    state VARCHAR(32) NOT NULL,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    provider_request_id VARCHAR(128),
    provider_act_code VARCHAR(16),
    bank_rrn VARCHAR(32),
    decline_kind VARCHAR(16),
    created_by VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_disbursement_intent_tran_ref_no ON disbursement_intent (tran_ref_no);

CREATE UNIQUE INDEX uk_disbursement_intent_live_account
    ON disbursement_intent (loan_account_id)
    WHERE state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED');

CREATE INDEX idx_disbursement_intent_claimable
    ON disbursement_intent (state, lease_expires_at)
    WHERE state = 'CREATED';

-- Backfill in-flight disbursements so reconciliation can continue after deploy.
INSERT INTO disbursement_intent (
    id,
    loan_account_id,
    tran_ref_no,
    amount,
    payment_mode,
    beneficiary_name,
    beneficiary_account_number,
    beneficiary_ifsc,
    state,
    attempt_count,
    provider_request_id,
    provider_act_code,
    bank_rrn,
    decline_kind,
    created_by,
    correlation_id,
    created_at,
    updated_at
)
SELECT
    gen_random_uuid(),
    la.id,
    COALESCE(log.tran_ref_no, 'ICI' || UPPER(SUBSTRING(REPLACE(log.id::text, '-', ''), 1, 13))),
    log.amount,
    COALESCE(log.payment_mode, 'IMPS'),
    COALESCE(b.account_holder_name, b.full_name),
    b.bank_account_number,
    b.ifsc_code,
    'UNKNOWN',
    0,
    log.provider_request_id,
    log.provider_act_code,
    log.bank_rrn,
    log.decline_kind,
    log.actor_username,
    log.correlation_id,
    log.created_at,
    log.updated_at
FROM loan_account la
JOIN loan_disbursement_request_log log
    ON log.loan_account_id = la.id
JOIN borrower b ON b.id = la.borrower_id
WHERE la.status = 'DISBURSEMENT_REQUESTED'
  AND log.id = (
      SELECT l2.id
      FROM loan_disbursement_request_log l2
      WHERE l2.loan_account_id = la.id
      ORDER BY l2.created_at DESC
      LIMIT 1
  )
  AND NOT EXISTS (
      SELECT 1
      FROM disbursement_intent di
      WHERE di.loan_account_id = la.id
        AND di.state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
  );
