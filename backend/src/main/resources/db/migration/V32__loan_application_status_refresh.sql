WITH application_status_mapping AS (
    SELECT
        la.id AS application_id,
        CASE
            WHEN la.status = 'RECEIVED' THEN 'INITIALIZED'
            WHEN la.status = 'UNDER_REVIEW' THEN 'AWAITING_APPROVAL'
            WHEN la.status = 'HOLD' THEN 'AWAITING_APPROVAL'
            WHEN la.status = 'APPROVED' THEN CASE
                WHEN EXISTS (
                    SELECT 1
                    FROM loan_account account
                    WHERE account.loan_application_id = la.id
                      AND account.status IN ('CLOSED', 'FORECLOSED')
                ) THEN 'CLOSED'
                WHEN EXISTS (
                    SELECT 1
                    FROM loan_account account
                    JOIN loan_payment_transaction payment
                      ON payment.loan_account_id = account.id
                    WHERE account.loan_application_id = la.id
                      AND account.status = 'DISBURSED'
                      AND payment.status = 'RECEIVED'
                ) THEN 'UNDER_REPAYMENT'
                WHEN EXISTS (
                    SELECT 1
                    FROM loan_account account
                    WHERE account.loan_application_id = la.id
                      AND account.status = 'DISBURSED'
                ) THEN 'DISBURSED'
                WHEN EXISTS (
                    SELECT 1
                    FROM loan_account account
                    WHERE account.loan_application_id = la.id
                      AND account.status IN ('DISBURSEMENT_FAILED', 'DISBURSEMENT_PENDING_RECONCILIATION')
                ) THEN 'PAYMENT_REINITIATION'
                ELSE 'APPROVED_PENDING_DISBURSAL'
            END
            ELSE la.status
        END AS new_status
    FROM loan_application la
)
UPDATE loan_application la
SET status = mapped.new_status
FROM application_status_mapping mapped
WHERE la.id = mapped.application_id
  AND la.status <> mapped.new_status;

UPDATE loan_application_status_transition
SET from_status = CASE
        WHEN from_status = 'RECEIVED' THEN 'INITIALIZED'
        WHEN from_status = 'UNDER_REVIEW' THEN 'AWAITING_APPROVAL'
        WHEN from_status = 'HOLD' THEN 'AWAITING_APPROVAL'
        WHEN from_status = 'APPROVED' THEN 'APPROVED_PENDING_DISBURSAL'
        ELSE from_status
    END,
    to_status = CASE
        WHEN to_status = 'RECEIVED' THEN 'INITIALIZED'
        WHEN to_status = 'UNDER_REVIEW' THEN 'AWAITING_APPROVAL'
        WHEN to_status = 'HOLD' THEN 'AWAITING_APPROVAL'
        WHEN to_status = 'APPROVED' THEN 'APPROVED_PENDING_DISBURSAL'
        ELSE to_status
    END
WHERE from_status IN ('RECEIVED', 'UNDER_REVIEW', 'HOLD', 'APPROVED')
   OR to_status IN ('RECEIVED', 'UNDER_REVIEW', 'HOLD', 'APPROVED');

UPDATE loan_application_audit_event
SET from_status = CASE
        WHEN from_status = 'RECEIVED' THEN 'INITIALIZED'
        WHEN from_status = 'UNDER_REVIEW' THEN 'AWAITING_APPROVAL'
        WHEN from_status = 'HOLD' THEN 'AWAITING_APPROVAL'
        WHEN from_status = 'APPROVED' THEN 'APPROVED_PENDING_DISBURSAL'
        ELSE from_status
    END,
    to_status = CASE
        WHEN to_status = 'RECEIVED' THEN 'INITIALIZED'
        WHEN to_status = 'UNDER_REVIEW' THEN 'AWAITING_APPROVAL'
        WHEN to_status = 'HOLD' THEN 'AWAITING_APPROVAL'
        WHEN to_status = 'APPROVED' THEN 'APPROVED_PENDING_DISBURSAL'
        ELSE to_status
    END
WHERE from_status IN ('RECEIVED', 'UNDER_REVIEW', 'HOLD', 'APPROVED')
   OR to_status IN ('RECEIVED', 'UNDER_REVIEW', 'HOLD', 'APPROVED');
