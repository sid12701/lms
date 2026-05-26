-- F-09: Widen loan_application_intake_audit.payload_json from VARCHAR(4000) to TEXT.
--
-- The intake payload captures the raw borrower onboarding submission for forensics.
-- After V33 added ~20 new onboarding fields, 4 KB is plausibly tight and truncation
-- would silently lose data. TEXT is an online type change in Postgres (no rewrite).
-- See docs/database-sql-review.md F-09 / issue #28.
ALTER TABLE loan_application_intake_audit
    ALTER COLUMN payload_json TYPE TEXT;
