-- Gap #3 — Unified cross-domain audit search.
--
-- Adds the indexes required by the new GET /api/v1/internal/admin/audit-events
-- endpoint, which executes a native UNION ALL across the four audit tables and
-- supports filter pushdown by actor_username, loan_application_id (or
-- loan_product_id), and time range. Each table already has a
-- (subject_id, created_at DESC) index; we add the missing actor-by-time and
-- created-at global indexes so the unified scan stays index-driven.

CREATE INDEX IF NOT EXISTS idx_loan_application_audit_event_created_at
    ON loan_application_audit_event (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_application_audit_event_actor_created_at
    ON loan_application_audit_event (actor_username, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_application_intake_audit_global_created_at
    ON loan_application_intake_audit (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_application_intake_audit_actor_created_at
    ON loan_application_intake_audit (actor_username, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_document_access_audit_created_at
    ON loan_application_document_access_audit (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_document_access_audit_actor_created_at
    ON loan_application_document_access_audit (actor_username, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_product_audit_event_created_at
    ON loan_product_audit_event (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loan_product_audit_event_actor_created_at
    ON loan_product_audit_event (actor_username, created_at DESC);
