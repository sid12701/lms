CREATE TABLE loan_product_audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_product_id UUID NOT NULL REFERENCES loan_product(id) ON DELETE CASCADE,
    action VARCHAR(64) NOT NULL,
    actor_username VARCHAR(255) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_product_audit_event_product_created_at
    ON loan_product_audit_event (loan_product_id, created_at DESC);
