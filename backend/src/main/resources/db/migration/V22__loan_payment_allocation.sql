ALTER TABLE loan_repayment_schedule_installment
    ADD COLUMN status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN paid_principal NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN paid_interest NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN paid_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN outstanding_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE loan_repayment_schedule_installment
SET outstanding_amount = installment_amount,
    updated_at = created_at;

ALTER TABLE loan_payment_transaction
    ADD COLUMN allocated_amount NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN unallocated_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

UPDATE loan_payment_transaction
SET unallocated_amount = amount;
