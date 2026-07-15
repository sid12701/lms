CREATE TABLE borrower_pii_reveal_audit (
    id UUID PRIMARY KEY,
    borrower_id UUID NOT NULL REFERENCES borrower (id) ON DELETE CASCADE,
    lsp_id UUID REFERENCES lsp (id),
    actor_username VARCHAR(255) NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    revealed_fields VARCHAR(255) NOT NULL,
    client_ip VARCHAR(64),
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_borrower_pii_reveal_audit_borrower_created
    ON borrower_pii_reveal_audit (borrower_id, created_at DESC);
