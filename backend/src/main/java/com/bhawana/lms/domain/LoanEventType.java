package com.bhawana.lms.domain;

public enum LoanEventType {
    LOAN_CREATED,
    LOAN_STATUS_CHANGED,
    DISBURSEMENT_REQUESTED,
    DISBURSEMENT_COMPLETED,
    DISBURSEMENT_FAILED,
    /**
     * The loan account is parked awaiting reconciliation (CONTEXT.md § Disbursement,
     * "Reconciliation" / "Point of no return"): the debit leg may have already left the LSP's
     * disbursal account, and the bank's status checks have not yet resolved to a terminal outcome.
     * Named to mirror {@link com.bhawana.lms.domain.LoanAccountStatus#DISBURSEMENT_PENDING_RECONCILIATION}
     * exactly, so a partner reads the same word the platform uses internally.
     */
    DISBURSEMENT_PENDING_RECONCILIATION,
    DOCUMENTS_UPLOADED,
    LOAN_REPAYMENT_RECORDED,
    LOAN_FULLY_REPAID,
    FORECLOSURE_QUOTE_REQUESTED,
    LOAN_FORECLOSURE_COMPLETED,
    BORROWER_BANK_DETAILS_UPDATED,
    /**
     * The loan's days-past-due bucket changed (CONTEXT.md § Partner lifecycle updates; ADR 0007
     * Consequences — {@code loan_delinquency_state} gains durable history it never had). Fires in both
     * directions: worsening, when the loan enters or advances further into delinquency, and curing, when
     * {@code payload.toBucket} reads {@code CURRENT} again. Emitted only when the bucket itself changes,
     * not on every days-past-due tick a scheduled evaluation re-confirms.
     */
    LOAN_DELINQUENCY_BUCKET_CHANGED
}
