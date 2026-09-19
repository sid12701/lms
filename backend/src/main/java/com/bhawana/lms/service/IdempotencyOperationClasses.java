package com.bhawana.lms.service;

import java.util.Map;

/**
 * Static operation-key → {@link IdempotencyOperationClass} classification used by
 * {@link IdempotencyExecutionCoordinator} when a reclaimed pending record must
 * decide between re-execution and an explicit recovery-required terminal state.
 *
 * <p>The keys are the operation-key string constants declared at each call site
 * (controllers keep their own constants). The default for any unlisted key is
 * {@link IdempotencyOperationClass#RECONCILIATION_REQUIRED}: a new or renamed
 * operation degrades to the safe terminal state instead of being re-executed on
 * an assumption this table has not verified.
 */
public final class IdempotencyOperationClasses {

    private static final Map<String, IdempotencyOperationClass> CLASSES = Map.ofEntries(
            // LSP-scope operations whose action is a single database transaction.
            Map.entry("LOAN_APPLICATION_CREATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("LOAN_APPLICATION_INVALIDATION", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("LOAN_DOCUMENT_METADATA_SUBMIT", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("API_CLIENT_CREATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("API_CLIENT_ROTATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            // Foreclosure execution is additionally replay-safe on its own: an
            // already-executed quote resolves its committed settlement from the
            // receipt's stored request fingerprint.
            Map.entry("FORECLOSURE_EXECUTE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("OPS_FORECLOSURE_EXECUTE", IdempotencyOperationClass.DATABASE_ATOMIC),

            // Admin-scope single-transaction actions.
            Map.entry("OPS_STATUS_TRANSITION", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("OPS_MANUAL_STATUS_OVERRIDE", IdempotencyOperationClass.DATABASE_ATOMIC),
            // Initiation only commits the durable disbursement intent; a committed
            // prior intent makes re-execution fail closed (DISBURSEMENT_ALREADY_REQUESTED)
            // rather than double-initiate.
            Map.entry("OPS_DISBURSEMENT_INITIATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            // Simulation-only outcome application; the applier commits one
            // terminal result atomically.
            Map.entry("OPS_DISBURSEMENT_MOCK_OUTCOME", IdempotencyOperationClass.DATABASE_ATOMIC),
            // Consumes one stored provider observation; replay-safe by contract.
            Map.entry("OPS_DISBURSEMENT_RECONCILE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("ALERT_ACKNOWLEDGE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("ALERT_ESCALATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("LSP_CREATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("LSP_STATUS_UPDATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("PRODUCT_CREATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("PRODUCT_UPDATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("LSP_API_IP_ALLOWLIST_CREATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("LSP_UI_IP_ALLOWLIST_CREATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("USER_CREATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("USER_UPDATE", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("USER_RESET_PASSWORD", IdempotencyOperationClass.DATABASE_ATOMIC),
            Map.entry("REPORT_REQUEST_CREATE", IdempotencyOperationClass.DATABASE_ATOMIC),

            // Document uploads write object storage before the checklist row
            // commits. The storage key is content-addressed
            // (loan/{applicationId}/{type}/{sha256}-{fileName}), so a re-executed
            // store overwrites the same object identity rather than stacking
            // orphans, and LoanDocumentUploadIdempotencyReconstructor recovers
            // from committed checklist evidence first.
            Map.entry("LOAN_DOCUMENT_UPLOAD", IdempotencyOperationClass.EXTERNALLY_IDEMPOTENT),
            Map.entry("LOAN_DOCUMENT_BATCH_UPLOAD", IdempotencyOperationClass.EXTERNALLY_IDEMPOTENT),
            // A disbursement status check is the bank-prescribed repeatable poll
            // of the frozen original reference; re-execution is recovery, not
            // duplication. Each poll commits its own sequence as evidence before
            // the provider call.
            Map.entry("OPS_DISBURSEMENT_STATUS_CHECK", IdempotencyOperationClass.EXTERNALLY_IDEMPOTENT)
    );

    private IdempotencyOperationClasses() {
    }

    /**
     * Classify an operation key. Unknown keys are
     * {@link IdempotencyOperationClass#RECONCILIATION_REQUIRED} — the
     * failure-safe direction, since their post-crash outcome was never audited.
     */
    public static IdempotencyOperationClass classify(String operationKey) {
        return CLASSES.getOrDefault(operationKey, IdempotencyOperationClass.RECONCILIATION_REQUIRED);
    }

    /** True when a reclaimed attempt may re-run the action if evidence recovery missed. */
    public static boolean isReexecutable(String operationKey) {
        return classify(operationKey) != IdempotencyOperationClass.RECONCILIATION_REQUIRED;
    }
}
