-- Mock ICICI Composite Pay disbursement: capture the money-movement rail and the provider's
-- ActCode / BankRRN / Technical-vs-Business decline classification on each disbursement attempt,
-- plus a status-check poll counter for the deferred (pending) lifecycle.
--
-- All columns are nullable / defaulted forward-only: legacy rows predate the mock-ICICI adapter
-- and stay valid without backfill.
ALTER TABLE loan_disbursement_request_log
    ADD COLUMN payment_mode VARCHAR(16),
    ADD COLUMN tran_ref_no VARCHAR(64),
    ADD COLUMN provider_act_code VARCHAR(16),
    ADD COLUMN bank_rrn VARCHAR(32),
    ADD COLUMN decline_kind VARCHAR(16),
    ADD COLUMN status_check_count INTEGER NOT NULL DEFAULT 0;
