CREATE TABLE loan_product_lsp_mapping (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_product_id UUID NOT NULL REFERENCES loan_product(id) ON DELETE CASCADE,
    lsp_id UUID NOT NULL REFERENCES lsp(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_loan_product_lsp_mapping UNIQUE (loan_product_id, lsp_id)
);
