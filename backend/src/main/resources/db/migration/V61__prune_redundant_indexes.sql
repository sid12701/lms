-- F-08: Drop indexes superseded by later composites or implicit unique indexes.
--
-- Each redundant index here is fully covered (for leading-column scans or unique
-- lookups) by an index introduced in a later migration. Keeping them costs write
-- amplification on every INSERT/UPDATE and disk on every backup, with no read
-- benefit. See docs/database-sql-review.md F-08 / issue #27.
--
-- Mapping (dropped -> superseded by):
--   idx_loan_application_status            -> idx_loan_application_status_created_at        (V48)
--   idx_loan_application_lsp_id            -> idx_loan_application_lsp_created_at           (V48)
--   idx_loan_application_loan_product_id   -> idx_loan_application_product_created_at       (V48)
--   idx_loan_application_created_at        -> V48 composites (leading-column scans)
--   idx_loan_account_lsp_disbursed_at      -> idx_loan_account_lsp_disbursed_created_at     (V48)
--   idx_borrower_pan                       -> uk_borrower_pan UNIQUE                        (V43)
--   idx_loan_account_loan_application_id   -> UNIQUE (loan_application_id) implicit index   (V17)
DROP INDEX IF EXISTS idx_loan_application_status;
DROP INDEX IF EXISTS idx_loan_application_lsp_id;
DROP INDEX IF EXISTS idx_loan_application_loan_product_id;
DROP INDEX IF EXISTS idx_loan_application_created_at;
DROP INDEX IF EXISTS idx_loan_account_lsp_disbursed_at;
DROP INDEX IF EXISTS idx_borrower_pan;
DROP INDEX IF EXISTS idx_loan_account_loan_application_id;
