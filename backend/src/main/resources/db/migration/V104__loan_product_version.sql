CREATE TABLE loan_product_version (
    id UUID PRIMARY KEY,
    loan_product_id UUID NOT NULL REFERENCES loan_product(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    min_principal NUMERIC(19, 2) NOT NULL,
    max_principal NUMERIC(19, 2) NOT NULL,
    interest_rate NUMERIC(5, 2) NOT NULL,
    processing_fee_rate NUMERIC(5, 2) NOT NULL,
    min_tenure_months INTEGER NOT NULL,
    max_tenure_months INTEGER NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (loan_product_id, version_number)
);

INSERT INTO loan_product_version (
    id,
    loan_product_id,
    version_number,
    min_principal,
    max_principal,
    interest_rate,
    processing_fee_rate,
    min_tenure_months,
    max_tenure_months,
    effective_from,
    created_at
)
SELECT
    gen_random_uuid(),
    id,
    1,
    min_principal,
    max_principal,
    interest_rate,
    processing_fee_rate,
    min_tenure_months,
    max_tenure_months,
    created_at,
    created_at
FROM loan_product;

ALTER TABLE loan_application
    ADD COLUMN loan_product_version_id UUID REFERENCES loan_product_version(id);

UPDATE loan_application la
SET loan_product_version_id = lpv.id
FROM loan_product_version lpv
WHERE lpv.loan_product_id = la.loan_product_id
  AND lpv.version_number = 1;

ALTER TABLE loan_application
    ALTER COLUMN loan_product_version_id SET NOT NULL;

CREATE INDEX idx_loan_application_loan_product_version_id
    ON loan_application (loan_product_version_id);

ALTER TABLE loan_account
    ADD COLUMN loan_product_version_id UUID REFERENCES loan_product_version(id);

UPDATE loan_account la
SET loan_product_version_id = lpv.id
FROM loan_product_version lpv
WHERE lpv.loan_product_id = la.loan_product_id
  AND lpv.version_number = 1;

ALTER TABLE loan_account
    ALTER COLUMN loan_product_version_id SET NOT NULL;

CREATE INDEX idx_loan_account_loan_product_version_id
    ON loan_account (loan_product_version_id);

DO $$
BEGIN
    EXECUTE format('GRANT SELECT ON TABLE loan_product_version TO %I', '${tenant_app_role}');
END
$$;
