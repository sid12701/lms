-- Idempotent: Supabase may already have these columns from an earlier V80 duplicate.
ALTER TABLE loan_application_document_access_audit
    ADD COLUMN IF NOT EXISTS actor_ip varchar(64),
    ADD COLUMN IF NOT EXISTS byte_count bigint;
