ALTER TABLE loan_application_document_checklist
    ADD COLUMN lms_managed_content BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN storage_key VARCHAR(500),
    ADD COLUMN file_checksum VARCHAR(128),
    ADD COLUMN file_size_bytes BIGINT;

ALTER TABLE loan_payment_transaction
    ADD COLUMN repayment_installment_id UUID REFERENCES loan_repayment_schedule_installment (id);

CREATE INDEX idx_loan_payment_transaction_installment
    ON loan_payment_transaction (repayment_installment_id);
