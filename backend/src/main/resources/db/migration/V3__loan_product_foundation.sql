CREATE TABLE loan_product (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    min_principal NUMERIC(19, 2) NOT NULL,
    max_principal NUMERIC(19, 2) NOT NULL,
    interest_rate NUMERIC(5, 2) NOT NULL,
    processing_fee_rate NUMERIC(5, 2) NOT NULL,
    min_tenure_months INTEGER NOT NULL,
    max_tenure_months INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
