CREATE INDEX IF NOT EXISTS idx_loan_application_lsp_created_at
    ON loan_application (lsp_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_application_product_created_at
    ON loan_application (loan_product_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_application_status_created_at
    ON loan_application (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_application_source_channel_created_at
    ON loan_application (source_channel, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_account_lsp_disbursed_created_at
    ON loan_account (lsp_id, disbursed_at DESC, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_app_user_lsp_username
    ON app_user (lsp_id, username);

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_borrower_full_name_trgm
    ON borrower USING gin (lower(full_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_borrower_pan_trgm
    ON borrower USING gin (lower(pan) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_borrower_mobile_trgm
    ON borrower USING gin (lower(mobile) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_loan_application_external_loan_id_trgm
    ON loan_application USING gin (lower(external_loan_id) gin_trgm_ops);
