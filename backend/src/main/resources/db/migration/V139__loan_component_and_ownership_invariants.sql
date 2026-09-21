-- M18 — enforce component arithmetic and cross-entity ownership at the database
-- boundary. V65 covered nonnegative amounts and paid/outstanding totals; these
-- invariants were previously enforced only inside the servicing/schedule service
-- paths, so a hand edit, a restore, or a future writer could corrupt them
-- silently.
--
-- Every constraint is added NOT VALID and then VALIDATEd in the same migration:
-- NOT VALID keeps the DDL off a long table scan, and validation makes a dirty
-- pre-existing row fail the migration loudly rather than slipping past. Before
-- applying to a populated database, run the violation-inventory queries recorded
-- in docs/adr/0014... (per-constraint count queries) — each must return zero.
--
-- Deferred on purpose: loan_foreclosure_quote's settlement = principal + interest
-- equality. H08's pricing policy (ADR 0009, still Proposed) has not fixed which
-- components sum into settlement_amount; only nonnegativity — true under every
-- pricing outcome — is enforced here.

-- 1. Installment component arithmetic and paid-component bounds.
--    Matches what LoanRepaymentScheduleService generates and what
--    validateProvidedInstallments already demands of LSP-supplied schedules.
ALTER TABLE loan_repayment_schedule_installment
    ADD CONSTRAINT chk_installment_amount_components
        CHECK (installment_amount = principal_due + interest_due) NOT VALID,
    ADD CONSTRAINT chk_installment_principal_reconcile
        CHECK (closing_principal = opening_principal - principal_due) NOT VALID,
    ADD CONSTRAINT chk_installment_paid_principal_bounded
        CHECK (paid_principal <= principal_due) NOT VALID,
    ADD CONSTRAINT chk_installment_paid_interest_bounded
        CHECK (paid_interest <= interest_due) NOT VALID;

-- 2. Foreclosure quote components are nonnegative under any pricing policy.
ALTER TABLE loan_foreclosure_quote
    ADD CONSTRAINT chk_foreclosure_quote_outstanding_principal_non_negative
        CHECK (outstanding_principal >= 0) NOT VALID,
    ADD CONSTRAINT chk_foreclosure_quote_outstanding_interest_non_negative
        CHECK (outstanding_interest >= 0) NOT VALID,
    ADD CONSTRAINT chk_foreclosure_quote_settlement_amount_non_negative
        CHECK (settlement_amount >= 0) NOT VALID;

-- 3. Uniqueness anchors the ownership FKs reference. Each is trivially unique
--    (the first column is already the primary key); the composite shape lets the
--    FK carry the "same account / same product / same application" invariant.
ALTER TABLE loan_repayment_schedule_installment
    ADD CONSTRAINT uk_installment_id_loan_account UNIQUE (id, loan_account_id);
ALTER TABLE loan_foreclosure_quote
    ADD CONSTRAINT uk_foreclosure_quote_id_loan_account UNIQUE (id, loan_account_id);
ALTER TABLE loan_product_version
    ADD CONSTRAINT uk_loan_product_version_id_product UNIQUE (id, loan_product_id);
ALTER TABLE loan_application
    ADD CONSTRAINT uk_loan_application_identity
        UNIQUE (id, borrower_id, lsp_id, loan_product_id, loan_product_version_id);

-- 4. Cross-entity agreement, enforced by composite foreign keys. A NULL
--    repayment_installment_id / foreclosure_quote_id skips the check
--    (MATCH SIMPLE), so untargeted receipts are unaffected.
ALTER TABLE loan_payment_transaction
    ADD CONSTRAINT fk_payment_installment_same_account
        FOREIGN KEY (repayment_installment_id, loan_account_id)
        REFERENCES loan_repayment_schedule_installment (id, loan_account_id) NOT VALID,
    ADD CONSTRAINT fk_payment_foreclosure_quote_same_account
        FOREIGN KEY (foreclosure_quote_id, loan_account_id)
        REFERENCES loan_foreclosure_quote (id, loan_account_id) NOT VALID;

ALTER TABLE loan_application
    ADD CONSTRAINT fk_application_version_same_product
        FOREIGN KEY (loan_product_version_id, loan_product_id)
        REFERENCES loan_product_version (id, loan_product_id) NOT VALID;

ALTER TABLE loan_account
    ADD CONSTRAINT fk_account_version_same_product
        FOREIGN KEY (loan_product_version_id, loan_product_id)
        REFERENCES loan_product_version (id, loan_product_id) NOT VALID,
    ADD CONSTRAINT fk_account_matches_application
        FOREIGN KEY (loan_application_id, borrower_id, lsp_id, loan_product_id, loan_product_version_id)
        REFERENCES loan_application (id, borrower_id, lsp_id, loan_product_id, loan_product_version_id) NOT VALID;

-- 5. Validate: scans existing rows under SHARE UPDATE EXCLUSIVE and fails the
--    migration with the offending row if the inventory missed a violation.
ALTER TABLE loan_repayment_schedule_installment
    VALIDATE CONSTRAINT chk_installment_amount_components,
    VALIDATE CONSTRAINT chk_installment_principal_reconcile,
    VALIDATE CONSTRAINT chk_installment_paid_principal_bounded,
    VALIDATE CONSTRAINT chk_installment_paid_interest_bounded;

ALTER TABLE loan_foreclosure_quote
    VALIDATE CONSTRAINT chk_foreclosure_quote_outstanding_principal_non_negative,
    VALIDATE CONSTRAINT chk_foreclosure_quote_outstanding_interest_non_negative,
    VALIDATE CONSTRAINT chk_foreclosure_quote_settlement_amount_non_negative;

ALTER TABLE loan_payment_transaction
    VALIDATE CONSTRAINT fk_payment_installment_same_account,
    VALIDATE CONSTRAINT fk_payment_foreclosure_quote_same_account;

ALTER TABLE loan_application
    VALIDATE CONSTRAINT fk_application_version_same_product;

ALTER TABLE loan_account
    VALIDATE CONSTRAINT fk_account_version_same_product,
    VALIDATE CONSTRAINT fk_account_matches_application;
