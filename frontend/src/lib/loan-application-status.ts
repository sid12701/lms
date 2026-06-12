/**
 * Canonical loan-application statuses — mirrors backend `LoanApplicationStatus`.
 * Single module for F2 OpenAPI type replacement later.
 */
export const LOAN_APPLICATION_STATUSES = [
  "INITIALIZED",
  "AWAITING_APPROVAL",
  "APPROVED_PENDING_DISBURSAL",
  "REJECTED",
  "DISBURSEMENT_RETRY",
  "INVALID",
  "DISBURSED",
  "UNDER_REPAYMENT",
  "CLOSED",
  "FORECLOSED",
] as const;

export type LoanApplicationStatus = (typeof LOAN_APPLICATION_STATUSES)[number];

const STATUS_SET = new Set<string>(LOAN_APPLICATION_STATUSES);

export function isLoanApplicationStatus(value: string): value is LoanApplicationStatus {
  return STATUS_SET.has(value);
}

/** Label for values outside the canonical enum (drift is visible, not folded). */
export function unknownLoanApplicationStatusLabel(raw: string): string {
  const trimmed = raw.trim();
  return trimmed ? `Unknown (${trimmed})` : "Unknown";
}

/**
 * Parse API status strings. Returns null when the backend sent an unknown value.
 */
export function parseLoanApplicationStatus(
  raw: string | null | undefined,
): LoanApplicationStatus | null {
  if (!raw) return null;
  return isLoanApplicationStatus(raw) ? raw : null;
}

export type UnknownLoanStatus = `UNKNOWN:${string}`;
export type LoanStatusOrUnknown = LoanApplicationStatus | UnknownLoanStatus;

/** Map API status strings without folding unknown values to INITIALIZED. */
export function apiLoanStatus(raw: string | null | undefined): LoanStatusOrUnknown {
  const parsed = parseLoanApplicationStatus(raw);
  if (parsed) return parsed;
  return `UNKNOWN:${raw ?? ""}`;
}

/** Backend adjacency matrix — must match `LoanApplicationStatus.canTransitionTo`. */
export const BACKEND_ALLOWED_TRANSITIONS: Readonly<
  Record<LoanApplicationStatus, readonly LoanApplicationStatus[]>
> = {
  INITIALIZED: ["AWAITING_APPROVAL", "INVALID"],
  AWAITING_APPROVAL: ["APPROVED_PENDING_DISBURSAL", "REJECTED", "INVALID"],
  APPROVED_PENDING_DISBURSAL: ["DISBURSED", "DISBURSEMENT_RETRY", "INVALID"],
  DISBURSEMENT_RETRY: ["DISBURSED", "DISBURSEMENT_RETRY", "INVALID"],
  DISBURSED: ["UNDER_REPAYMENT", "CLOSED", "FORECLOSED"],
  UNDER_REPAYMENT: ["CLOSED", "FORECLOSED"],
  REJECTED: [],
  INVALID: [],
  CLOSED: [],
  FORECLOSED: [],
};
