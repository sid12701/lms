ALTER TABLE loan_repayment_schedule_installment
    ADD COLUMN entity_version BIGINT NOT NULL DEFAULT 0;
