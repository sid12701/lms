ALTER TABLE loan_payment_transaction
    ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);

DROP INDEX IF EXISTS uk_loan_payment_transaction_idempotency_key;

ALTER TABLE loan_payment_transaction
    ADD CONSTRAINT uk_loan_payment_transaction_idempotency_key UNIQUE (idempotency_key);
