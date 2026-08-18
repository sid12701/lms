import type { AlertSubjectType } from "@/schemas/alert";
import { delinquencyBucketLabel } from "@/lib/delinquency-display";
import { unknownLoanApplicationStatusLabel } from "@/lib/loan-application-status";

const SUBJECT_TYPE_LABELS: Record<AlertSubjectType, string> = {
  LOAN_APPLICATION: "Loan application",
  LOAN_ACCOUNT: "Loan account",
  BORROWER: "Borrower",
  REPORT_REQUEST: "Report request",
  SYSTEM: "System",
};

const DELINQUENCY_TITLE = /^Delinquency bucket ([A-Z0-9_]+)$/i;
const LSP_BOUND_VIOLATION_TITLE = /^LSP bound violation: ([A-Z0-9_]+)$/i;

/**
 * Human labels for `OpsAlertEmitters.emitLspBoundViolation`'s `violationType`
 * codes — the backend's `ScheduleViolationType` (LSP_PROVIDED schedule
 * validation, 12 members), `ForeclosureViolationType` (foreclosure execute-path
 * validation, 7 members), and 5 ad-hoc codes from the disbursement and
 * bank-detail paths that aren't backed by an enum. All of them reach the
 * frontend through the same `"LSP bound violation: " + violationType` title,
 * so one map covers every source rather than one per emitter.
 */
const LSP_BOUND_VIOLATION_LABELS: Record<string, string> = {
  // ScheduleViolationType (backend: com.bhawana.lms.service.ScheduleViolationType)
  SCHEDULE_OPENING_MISMATCH: "Schedule opening balance mismatch",
  SCHEDULE_CHAIN_BROKEN: "Schedule chain broken",
  SCHEDULE_FINAL_NONZERO: "Schedule does not close to zero",
  SCHEDULE_ROW_RECONCILE_FAILED: "Schedule row reconciliation failed",
  SCHEDULE_PRINCIPAL_NOT_CLOSED: "Schedule principal not fully closed",
  SCHEDULE_INSTALLMENT_COUNT_MISMATCH: "Schedule installment count mismatch",
  SCHEDULE_FIRST_DUE_OUT_OF_WINDOW: "First due date outside the approval window",
  SCHEDULE_CADENCE_VIOLATION: "Schedule cadence violation",
  SCHEDULE_HORIZON_EXCEEDED: "Schedule horizon exceeded",
  SCHEDULE_INTEREST_ROW_MISMATCH: "Schedule interest row mismatch",
  SCHEDULE_INTEREST_TOTAL_MISMATCH: "Schedule interest total mismatch",
  SCHEDULE_GENERIC: "Schedule validation failed",
  // ForeclosureViolationType (backend: com.bhawana.lms.service.ForeclosureViolationType)
  QUOTE_NOT_ACTIVE: "Foreclosure quote not active",
  QUOTE_OWNERSHIP_MISMATCH: "Foreclosure quote ownership mismatch",
  SETTLEMENT_DATE_MISMATCH: "Settlement date mismatch",
  LOAN_ACCOUNT_NOT_DISBURSED: "Loan account not disbursed",
  SETTLEMENT_INCOMPLETE: "Settlement incomplete",
  REFERENCE_MISSING: "Foreclosure reference missing",
  FORECLOSURE_GENERIC: "Foreclosure validation failed",
  // Ad-hoc codes — disbursement outcome + bank-detail paths, not backed by an enum
  MISSING_LOAN_ACCOUNT: "Loan account not available for disbursement",
  PROVIDER_BUSINESS_DECLINE: "Bank declined the disbursement",
  DISBURSEMENT_PENDING_RECONCILIATION: "Disbursement pending manual reconciliation",
  BANK_DETAIL_MISMATCH: "Repeated bank-detail mismatch",
  HOLDER_NAME_SOFT_MISMATCH: "Account holder name mismatch",
};

/**
 * Human label for an `emitLspBoundViolation` `violationType` code. Unmapped
 * codes degrade to `Unknown (raw)` rather than disappearing or presenting the
 * backend's enum spelling as if it were prose (audit F-07/14/19) — so a new
 * violation type stays readable, and visibly unmapped, until it lands above.
 */
export function lspBoundViolationLabel(violationType: string): string {
  const trimmed = violationType.trim();
  return LSP_BOUND_VIOLATION_LABELS[trimmed] ?? unknownLoanApplicationStatusLabel(trimmed);
}

/**
 * Backend alerts carry a stable, machine-parseable code in the title —
 * "Delinquency bucket DPD_1_30", "LSP bound violation: MISSING_LOAN_ACCOUNT" —
 * and the frontend humanizes it for display. Rewriting the title in Java would
 * make that code unstable for anything else parsing it, so the mapping lives
 * here, not on the backend.
 */
export function humanizeAlertTitle(title: string): string {
  const trimmed = title.trim();

  const delinquencyMatch = DELINQUENCY_TITLE.exec(trimmed);
  if (delinquencyMatch) {
    return `Delinquency · ${delinquencyBucketLabel(delinquencyMatch[1] ?? "")}`;
  }

  const violationMatch = LSP_BOUND_VIOLATION_TITLE.exec(trimmed);
  if (violationMatch) {
    return `LSP bound violation · ${lspBoundViolationLabel(violationMatch[1] ?? "")}`;
  }

  return title;
}

export function alertSubjectTypeLabel(subjectType: AlertSubjectType): string {
  return SUBJECT_TYPE_LABELS[subjectType] ?? subjectType.replace(/_/g, " ").toLowerCase();
}
