import type { LoanStatus } from "@/types";

/**
 * Statuses at which the money has already moved, so the pre-disbursement
 * document gate is behind the loan rather than ahead of it.
 *
 * Mirrors `LoanApplicationStatus.canTransitionTo`: `DISBURSED` is the first
 * state reachable only by completing disbursement, and nothing after it can
 * return to a pre-disbursal status.
 */
const POST_DISBURSEMENT: ReadonlySet<LoanStatus> = new Set<LoanStatus>([
  "DISBURSED",
  "UNDER_REPAYMENT",
  "CLOSED",
  "FORECLOSED",
]);

/**
 * Whether the disbursement document gate has already been passed (or bypassed).
 *
 * Drives the checklist's tense. "Required for disbursement" is a statement about
 * a gate the loan has yet to clear; repeating it on a loan whose funds are with
 * the borrower asserts a blocker that no longer exists and that nobody can act
 * on. `undefined` means the caller does not know the status, in which case the
 * pre-disbursement wording is the safe default.
 */
export function isDisbursementGatePassed(status: LoanStatus | undefined): boolean {
  return status !== undefined && POST_DISBURSEMENT.has(status);
}
