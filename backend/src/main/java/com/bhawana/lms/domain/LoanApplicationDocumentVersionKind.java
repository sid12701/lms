package com.bhawana.lms.domain;

/** How an immutable {@link LoanApplicationDocumentVersion} came to exist. */
public enum LoanApplicationDocumentVersionKind {
    /** Backfilled from the checklist row as it stood when versioning was introduced (V135). */
    LEGACY,
    /** An ordinary LSP submission. */
    SUBMISSION,
    /** An explicit, reasoned replacement of evidence an approval already committed against. */
    CORRECTION
}
