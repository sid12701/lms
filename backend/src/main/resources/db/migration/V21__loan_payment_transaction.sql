CREATE TABLE loan_payment_transaction (
    id UUID PRIMARY KEY,
    loan_account_id UUID NOT NULL REFERENCES loan_account (id),
    actor_username VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    payment_date DATE NOT NULL,
    reference VARCHAR(128) NOT NULL,
    channel VARCHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    note VARCHAR(500),
    correlation_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_loan_payment_transaction_account_payment_date
    ON loan_payment_transaction (loan_account_id, payment_date DESC, created_at DESC);
