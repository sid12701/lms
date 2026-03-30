CREATE TABLE borrower (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(255) NOT NULL,
    pan VARCHAR(10) NOT NULL UNIQUE,
    mobile VARCHAR(32) NOT NULL,
    email VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE loan_application (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    borrower_id UUID NOT NULL REFERENCES borrower(id) ON DELETE RESTRICT,
    lsp_id UUID NOT NULL REFERENCES lsp(id) ON DELETE RESTRICT,
    loan_product_id UUID NOT NULL REFERENCES loan_product(id) ON DELETE RESTRICT,
    external_loan_id VARCHAR(128) NOT NULL,
    source_channel VARCHAR(64) NOT NULL,
    requested_amount NUMERIC(19, 2) NOT NULL,
    tenure_months INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_loan_application_lsp_external UNIQUE (lsp_id, external_loan_id)
);

CREATE INDEX idx_loan_application_created_at ON loan_application (created_at DESC);
CREATE INDEX idx_loan_application_borrower_id ON loan_application (borrower_id);
