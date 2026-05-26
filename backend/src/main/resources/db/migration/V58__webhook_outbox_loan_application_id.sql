-- Gap #5: per-application webhook view requires a direct, indexed
-- loan-application linkage on the outbox. We cannot reuse `aggregate_id`
-- because LOAN_ACCOUNT and LOAN_PAYMENT_TRANSACTION rows carry a
-- non-application UUID there. Backfill each row from the most precise
-- linkage available for its aggregate_type.

ALTER TABLE webhook_event_outbox
    ADD COLUMN loan_application_id UUID;

UPDATE webhook_event_outbox
SET loan_application_id = CAST(aggregate_id AS UUID)
WHERE aggregate_type = 'LOAN_APPLICATION'
  AND loan_application_id IS NULL
  AND aggregate_id IS NOT NULL;

UPDATE webhook_event_outbox outbox
SET loan_application_id = account.loan_application_id
FROM loan_account account
WHERE outbox.aggregate_type = 'LOAN_ACCOUNT'
  AND outbox.loan_application_id IS NULL
  AND outbox.aggregate_id IS NOT NULL
  AND CAST(outbox.aggregate_id AS UUID) = account.id;

UPDATE webhook_event_outbox outbox
SET loan_application_id = account.loan_application_id
FROM loan_payment_transaction txn
JOIN loan_account account ON account.id = txn.loan_account_id
WHERE outbox.aggregate_type = 'LOAN_PAYMENT_TRANSACTION'
  AND outbox.loan_application_id IS NULL
  AND outbox.aggregate_id IS NOT NULL
  AND CAST(outbox.aggregate_id AS UUID) = txn.id;

CREATE INDEX idx_webhook_event_outbox_loan_application_created_at
    ON webhook_event_outbox (loan_application_id, created_at DESC);
