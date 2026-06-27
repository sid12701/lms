import { ApiError } from "@/lib/api/http-client";
import { formatRoleLabel, formatRoleList } from "@/lib/role-labels";
import { STATUS_META } from "@/lib/lifecycle";
import type { LoanStatus } from "@/types";
import { parseLoanApplicationStatus } from "@/lib/loan-application-status";

const IDEMPOTENCY_UUID_HINT = /Idempotency-Key must be a UUID v4/i;
const ISO8601_HINT = /ISO-8601|must be a valid.*date/i;
const MUST_NOT_BE_BLANK = /must not be blank/i;
const MUST_NOT_BE_NULL = /must not be null/i;
const UNKNOWN_LOAN_ID = /unknown loan application id/i;
const UNKNOWN_BORROWER_ID = /unknown borrower id/i;
const UNKNOWN_RESOURCE = /unknown .+ id:/i;
const ACCESS_CONTEXT = /access context|not authorized to access/i;

function friendlyValidationMessage(raw: string): string | null {
  if (MUST_NOT_BE_BLANK.test(raw)) return "This field is required.";
  if (MUST_NOT_BE_NULL.test(raw)) return "This field is required.";
  if (IDEMPOTENCY_UUID_HINT.test(raw)) {
    return "The request id must be a valid UUID. Generate a new one and retry.";
  }
  if (ISO8601_HINT.test(raw)) return "Enter a valid date.";
  return null;
}

function sanitizeBackendMessage(raw: string, fallback: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return fallback;
  if (UNKNOWN_LOAN_ID.test(trimmed)) {
    return "This loan application could not be found.";
  }
  if (UNKNOWN_BORROWER_ID.test(trimmed)) {
    return "This borrower could not be found.";
  }
  if (UNKNOWN_RESOURCE.test(trimmed)) {
    return "The requested record could not be found.";
  }
  if (ACCESS_CONTEXT.test(trimmed)) {
    return "You do not have permission to view this record.";
  }
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu.test(trimmed)) {
    return fallback;
  }
  return trimmed;
}

/** Map raw backend / transport errors to operator-friendly copy. */
export function mapApiErrorMessage(
  err: unknown,
  fallback = "Something went wrong. Please try again.",
): string {
  if (!(err instanceof ApiError)) {
    if (err instanceof Error && err.message.trim()) {
      return sanitizeBackendMessage(err.message, fallback);
    }
    return fallback;
  }

  const raw = err.message.trim();
  const friendlyValidation = friendlyValidationMessage(raw);
  if (friendlyValidation) return friendlyValidation;

  if (err.status === 404 || err.code === "NOT_FOUND") {
    return sanitizeBackendMessage(raw, "The requested record could not be found.");
  }
  if (err.status === 401 || err.status === 403 || err.code === "ACCESS_DENIED") {
    return "You do not have permission to perform this action.";
  }
  if (err.code === "DISBURSEMENT_VALIDATION_FAILED") {
    return "Disbursement checks failed. Review the bank details and try again.";
  }
  if (err.code === "REPAYMENT_SCHEDULE_INVALID") {
    return "The repayment schedule does not balance. Fix the installment totals and resubmit.";
  }
  if (err.code === "VALIDATION_FAILED") {
    return "Some fields are invalid. Review the form and try again.";
  }
  if (err.code === "DOCUMENT_STORAGE_UNAVAILABLE") {
    return "Document storage is temporarily unavailable. Please try again in a moment.";
  }

  return sanitizeBackendMessage(raw, fallback);
}

export function formatLoanStatusLabel(status: string): string {
  const parsed = parseLoanApplicationStatus(status);
  if (parsed && parsed in STATUS_META) {
    return STATUS_META[parsed as LoanStatus].label;
  }
  return status.replace(/_/g, " ").toLowerCase();
}

export function formatPermissionDeniedDescription(
  currentRole: string,
  allowedRoles: readonly string[],
): string {
  return `Signed in as ${formatRoleLabel(currentRole)}. This area is limited to ${formatRoleList(allowedRoles)}. Contact your administrator if you need access.`;
}
