-- F-02 wave 2a (and F-20): DB-side CHECK constraints as defence in depth.
-- Application writers already enforce these invariants; constraints guard against
-- bad SQL, restored backups, and future writers from corrupting state silently.
-- Regex constraints (PAN, Aadhaar, IFSC) are deferred to wave 2b after F-01
-- (PII encryption) is decided. Status enum constraints are deferred so the
-- enum vocabulary can evolve without per-state migrations.

-- loan_product
ALTER TABLE loan_product
    ADD CONSTRAINT chk_loan_product_min_principal_non_negative CHECK (min_principal >= 0),
    ADD CONSTRAINT chk_loan_product_max_principal_non_negative CHECK (max_principal >= 0),
    ADD CONSTRAINT chk_loan_product_principal_range CHECK (min_principal <= max_principal),
    ADD CONSTRAINT chk_loan_product_interest_rate_non_negative CHECK (interest_rate >= 0),
    ADD CONSTRAINT chk_loan_product_processing_fee_non_negative CHECK (processing_fee_rate >= 0),
    ADD CONSTRAINT chk_loan_product_min_tenure_positive CHECK (min_tenure_months > 0),
    ADD CONSTRAINT chk_loan_product_max_tenure_positive CHECK (max_tenure_months > 0),
    ADD CONSTRAINT chk_loan_product_tenure_range CHECK (min_tenure_months <= max_tenure_months);

-- loan_application
ALTER TABLE loan_application
    ADD CONSTRAINT chk_loan_application_requested_amount_non_negative CHECK (requested_amount >= 0),
    ADD CONSTRAINT chk_loan_application_tenure_positive CHECK (tenure_months > 0);

-- loan_account
ALTER TABLE loan_account
    ADD CONSTRAINT chk_loan_account_principal_non_negative CHECK (principal_amount >= 0),
    ADD CONSTRAINT chk_loan_account_tenure_positive CHECK (tenure_months > 0);

-- loan_repayment_schedule_installment (closes F-20)
ALTER TABLE loan_repayment_schedule_installment
    ADD CONSTRAINT chk_installment_number_positive CHECK (installment_number > 0),
    ADD CONSTRAINT chk_installment_opening_principal_non_negative CHECK (opening_principal >= 0),
    ADD CONSTRAINT chk_installment_principal_due_non_negative CHECK (principal_due >= 0),
    ADD CONSTRAINT chk_installment_interest_due_non_negative CHECK (interest_due >= 0),
    ADD CONSTRAINT chk_installment_amount_non_negative CHECK (installment_amount >= 0),
    ADD CONSTRAINT chk_installment_closing_principal_non_negative CHECK (closing_principal >= 0),
    ADD CONSTRAINT chk_installment_paid_principal_non_negative CHECK (paid_principal >= 0),
    ADD CONSTRAINT chk_installment_paid_interest_non_negative CHECK (paid_interest >= 0),
    ADD CONSTRAINT chk_installment_paid_amount_non_negative CHECK (paid_amount >= 0),
    ADD CONSTRAINT chk_installment_outstanding_non_negative CHECK (outstanding_amount >= 0),
    ADD CONSTRAINT chk_installment_paid_sum CHECK (paid_amount = paid_principal + paid_interest),
    ADD CONSTRAINT chk_installment_total CHECK (paid_amount + outstanding_amount = installment_amount);

-- loan_payment_transaction
ALTER TABLE loan_payment_transaction
    ADD CONSTRAINT chk_loan_payment_amount_non_negative CHECK (amount >= 0),
    ADD CONSTRAINT chk_loan_payment_allocated_non_negative CHECK (allocated_amount >= 0),
    ADD CONSTRAINT chk_loan_payment_unallocated_non_negative CHECK (unallocated_amount >= 0),
    ADD CONSTRAINT chk_loan_payment_allocation_total CHECK (allocated_amount + unallocated_amount = amount);
