-- Remap placeholder invalid-reason codes to partner-facing vocabulary.
-- OTHERS is unchanged to minimize churn on free-text invalidations.

UPDATE loan_application
SET invalid_reason_code = CASE invalid_reason_code
    WHEN 'REASON_A' THEN 'DUPLICATE_APPLICATION'
    WHEN 'REASON_B' THEN 'KYC_MISMATCH'
    WHEN 'REASON_C' THEN 'BORROWER_WITHDREW'
    ELSE invalid_reason_code
END
WHERE invalid_reason_code IN ('REASON_A', 'REASON_B', 'REASON_C');
