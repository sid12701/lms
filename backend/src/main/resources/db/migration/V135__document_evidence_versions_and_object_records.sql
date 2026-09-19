-- Approval & document evidence chain (consolidated audit H14, M04; H13 needs no schema).
--
-- Additive only. Three new tables and one nullable pointer on the checklist:
--
-- 1. loan_application_document_version — one immutable row per document write. The checklist
--    row stays the "current" view; current_version_id points at the version it shows.
-- 2. loan_application_approval_evidence — the exact version set (and checksums) an approval
--    committed against, one row per checklist item per approval.
-- 3. loan_document_object — durable ownership/state record for every object the LMS writes to
--    document storage. It is written (PENDING) before the object PUT, marked LINKED in the same
--    transaction as the metadata that references it, and only PENDING rows past a grace period
--    with no referencing metadata are ever deleted by the orphan reconciler. Objects written
--    before this migration have no row and are therefore never touched by the reconciler.
--
-- Backfill discipline: existing checklist rows that carry upload metadata become one LEGACY
-- version each. A LEGACY version records what the row shows today; it does not claim to
-- reconstruct files that earlier uploads overwrote. No approval evidence is backfilled for
-- already-approved applications: what the approver actually saw is not recoverable.

CREATE TABLE loan_application_document_version (
    id UUID PRIMARY KEY,
    loan_application_id UUID NOT NULL REFERENCES loan_application (id),
    checklist_item_id UUID NOT NULL REFERENCES loan_application_document_checklist (id),
    document_type VARCHAR(64) NOT NULL,
    version_number INTEGER NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('LEGACY', 'SUBMISSION', 'CORRECTION')),
    status VARCHAR(32) NOT NULL,
    note VARCHAR(500),
    file_name VARCHAR(255),
    file_reference VARCHAR(500),
    source_reference VARCHAR(500),
    content_type VARCHAR(128),
    lms_managed_content BOOLEAN NOT NULL,
    storage_key VARCHAR(500),
    file_checksum VARCHAR(128),
    file_size_bytes BIGINT,
    -- Lifecycle status of the application when the version was recorded; NULL for LEGACY.
    application_status VARCHAR(32),
    -- CORRECTION only: why approved evidence was replaced and which approval it corrects.
    correction_reason VARCHAR(500),
    corrects_evidence_id UUID,
    recorded_by_username VARCHAR(128),
    recorded_at TIMESTAMPTZ NOT NULL,
    UNIQUE (loan_application_id, document_type, version_number),
    CHECK (kind <> 'CORRECTION' OR correction_reason IS NOT NULL)
);

CREATE INDEX idx_loan_application_document_version_storage_key
    ON loan_application_document_version (storage_key)
    WHERE storage_key IS NOT NULL;

CREATE TABLE loan_application_approval_evidence (
    id UUID PRIMARY KEY,
    -- Groups the rows captured by one approval commit.
    approval_id UUID NOT NULL,
    loan_application_id UUID NOT NULL REFERENCES loan_application (id),
    document_type VARCHAR(64) NOT NULL,
    checklist_status VARCHAR(32) NOT NULL,
    -- NULL when the item had no recorded version (e.g. PENDING / NOT_REQUIRED).
    document_version_id UUID REFERENCES loan_application_document_version (id),
    file_checksum VARCHAR(128),
    storage_key VARCHAR(500),
    lms_managed_content BOOLEAN NOT NULL,
    approved_by_username VARCHAR(128),
    approved_at TIMESTAMPTZ NOT NULL,
    UNIQUE (approval_id, document_type)
);

CREATE INDEX idx_loan_application_approval_evidence_application
    ON loan_application_approval_evidence (loan_application_id, approved_at DESC);

ALTER TABLE loan_application_document_version
    ADD CONSTRAINT fk_document_version_corrects_evidence
    FOREIGN KEY (corrects_evidence_id) REFERENCES loan_application_approval_evidence (id);

ALTER TABLE loan_application_document_checklist
    ADD COLUMN current_version_id UUID REFERENCES loan_application_document_version (id);

INSERT INTO loan_application_document_version (
    id,
    loan_application_id,
    checklist_item_id,
    document_type,
    version_number,
    kind,
    status,
    note,
    file_name,
    file_reference,
    source_reference,
    content_type,
    lms_managed_content,
    storage_key,
    file_checksum,
    file_size_bytes,
    recorded_by_username,
    recorded_at
)
SELECT
    gen_random_uuid(),
    checklist.loan_application_id,
    checklist.id,
    checklist.document_type,
    1,
    'LEGACY',
    checklist.status,
    checklist.note,
    checklist.file_name,
    checklist.file_reference,
    checklist.source_reference,
    checklist.content_type,
    checklist.lms_managed_content,
    checklist.storage_key,
    checklist.file_checksum,
    checklist.file_size_bytes,
    COALESCE(checklist.uploaded_by_username, checklist.updated_by_username),
    COALESCE(checklist.uploaded_at, checklist.updated_at)
FROM loan_application_document_checklist checklist
WHERE checklist.file_name IS NOT NULL
   OR checklist.file_reference IS NOT NULL
   OR checklist.source_reference IS NOT NULL
   OR checklist.content_type IS NOT NULL;

UPDATE loan_application_document_checklist checklist
SET current_version_id = version.id
FROM loan_application_document_version version
WHERE version.checklist_item_id = checklist.id
  AND version.kind = 'LEGACY';

CREATE TABLE loan_document_object (
    storage_key VARCHAR(500) PRIMARY KEY,
    loan_application_id UUID NOT NULL REFERENCES loan_application (id),
    document_type VARCHAR(64) NOT NULL,
    file_checksum VARCHAR(128) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'LINKED', 'DELETING', 'DELETED')),
    -- Refreshed by every upload attempt that targets this key; the grace period runs from here.
    last_attempt_at TIMESTAMPTZ NOT NULL,
    linked_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_document_object_reconcile
    ON loan_document_object (last_attempt_at)
    WHERE state IN ('PENDING', 'DELETING');

-- Tenant access: versions and evidence are append-only for the tenant role (no UPDATE/DELETE);
-- object records need UPDATE for PENDING -> LINKED. Deletion is admin-only (reconciler).
DO $$
BEGIN
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE loan_application_document_version TO %I',
        '${tenant_app_role}'
    );
    EXECUTE format(
        'GRANT SELECT, INSERT ON TABLE loan_application_approval_evidence TO %I',
        '${tenant_app_role}'
    );
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE ON TABLE loan_document_object TO %I',
        '${tenant_app_role}'
    );
END
$$;

ALTER TABLE loan_application_document_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application_document_version FORCE ROW LEVEL SECURITY;
ALTER TABLE loan_application_approval_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_application_approval_evidence FORCE ROW LEVEL SECURITY;
ALTER TABLE loan_document_object ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_document_object FORCE ROW LEVEL SECURITY;

CREATE POLICY loan_application_document_version_tenant_policy
    ON loan_application_document_version
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_application(loan_application_id))
    WITH CHECK (tenant_owns_application(loan_application_id));

CREATE POLICY loan_application_approval_evidence_tenant_policy
    ON loan_application_approval_evidence
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_application(loan_application_id))
    WITH CHECK (tenant_owns_application(loan_application_id));

CREATE POLICY loan_document_object_tenant_policy
    ON loan_document_object
    FOR ALL
    TO ${tenant_app_role}
    USING (tenant_owns_application(loan_application_id))
    WITH CHECK (tenant_owns_application(loan_application_id));
