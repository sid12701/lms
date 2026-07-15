/**
 * Reason-code catalog for lifecycle transitions that require one.
 *
 * Mirrors backend `LoanApplicationStatusReasonCode` — the ops
 * status-transition endpoint rejects REJECTED / DISBURSEMENT_RETRY moves
 * without a code (`REASON_CODE_REQUIRED`), so the confirm dialog must
 * collect it alongside the free-text note.
 */
export const LIFECYCLE_REASON_CODES = [
  { value: "MISSING_DOCUMENTS", label: "Missing documents" },
  { value: "BORROWER_CLARIFICATION_REQUIRED", label: "Borrower clarification required" },
  { value: "POLICY_EXCEPTION", label: "Policy exception" },
  { value: "FAILED_VERIFICATION", label: "Failed verification" },
  { value: "DUPLICATE_APPLICATION", label: "Duplicate application" },
  { value: "MANUAL_ADMIN_OVERRIDE", label: "Manual admin override" },
] as const;
