package com.bhawana.lms.service;

/**
 * Recovery classification of an idempotent operation, consulted only after a
 * pending record's lease was reclaimed (i.e. the previous owner crashed or is
 * still running past its lease). The class decides what a re-executing attempt
 * is allowed to do; it never weakens the fingerprint/scope checks that run
 * before this point.
 */
public enum IdempotencyOperationClass {

    /**
     * The action's whole effect is one database transaction joined to the
     * idempotency completion write. A rolled-back attempt left nothing behind,
     * so the action may be re-executed under the existing owner/attempt
     * completion fence; a still-running previous owner loses that fence and
     * rolls back.
     */
    DATABASE_ATOMIC,

    /**
     * The action has a side effect outside its database transaction, but the
     * side effect carries deterministic identity (for example a
     * content-addressed document storage key) and/or committed evidence a
     * {@link IdempotencyResultReconstructor} can read back. Re-execution
     * converges on the same side effect instead of multiplying it.
     */
    EXTERNALLY_IDEMPOTENT,

    /**
     * The post-crash outcome is genuinely unknown and cannot be proven from
     * durable evidence. The pending record is evidence for reconciliation, so
     * it is transitioned to the terminal recovery-required state — never
     * deleted and never blindly re-executed.
     */
    RECONCILIATION_REQUIRED
}
