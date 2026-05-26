ALTER TABLE loan_payment_transaction
    ALTER COLUMN reference DROP NOT NULL;

ALTER TABLE loan_payment_transaction
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(36);

CREATE UNIQUE INDEX IF NOT EXISTS uk_loan_payment_transaction_idempotency_key
    ON loan_payment_transaction (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
