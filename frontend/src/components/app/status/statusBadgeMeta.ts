import { STATUS_META, type Intent } from "@/lib/lifecycle";
import {
  isLoanApplicationStatus,
  unknownLoanApplicationStatusLabel,
  type LoanStatusOrUnknown,
} from "@/lib/loan-application-status";
import type { LoanAccountStatus } from "@/types";
import { isLoanAccountStatus } from "@/schemas/loan-account";

export type AnyStatus = LoanStatusOrUnknown | LoanAccountStatus;

/** Gap #11 — delinquency aggregate for {@code UNDER_REPAYMENT} badge tone. */
export type StatusBadgeDelinquency = {
  maxDaysPastDue?: number | null;
  overdueInstallmentCount?: number | null;
} | null;

export interface ResolvedStatusMeta {
  label: string;
  intent: Intent;
}

const ACCOUNT_STATUS_META: Record<LoanAccountStatus, ResolvedStatusMeta> = {
  PENDING_DISBURSEMENT: { label: "Pending disbursement", intent: "progress" },
  DISBURSEMENT_REQUESTED: { label: "Disbursement requested", intent: "progress" },
  DISBURSED: { label: "Disbursed", intent: "success" },
  INVALID: { label: "Invalid", intent: "danger" },
  CLOSED: { label: "Closed", intent: "neutral" },
  FORECLOSED: { label: "Foreclosed", intent: "neutral" },
  DISBURSEMENT_FAILED: { label: "Disbursement failed", intent: "danger" },
  DISBURSEMENT_PENDING_RECONCILIATION: {
    label: "Pending reconciliation",
    intent: "warning",
  },
};

/**
 * Gap #11: visual tone for loan-status badges. {@code UNDER_REPAYMENT} is
 * success when on-track, danger when delinquency aggregates are present.
 */
function getUnderRepaymentBadgeTone(delinquency?: StatusBadgeDelinquency): "success" | "danger" {
  const dpd = delinquency?.maxDaysPastDue ?? 0;
  const overdue = delinquency?.overdueInstallmentCount ?? 0;
  return dpd > 0 || overdue > 0 ? "danger" : "success";
}

function toneToIntent(tone: "success" | "danger" | "neutral"): Intent {
  return tone;
}

function isUnknownLoanStatus(status: string): status is `UNKNOWN:${string}` {
  return status.startsWith("UNKNOWN:");
}

/** Lookup that succeeds for either enum. Always returns a non-empty meta. */
export function resolveStatusMeta(
  status: AnyStatus,
  options?: { delinquency?: StatusBadgeDelinquency },
): ResolvedStatusMeta {
  if (isUnknownLoanStatus(status)) {
    const raw = status.slice("UNKNOWN:".length);
    return { label: unknownLoanApplicationStatusLabel(raw), intent: "neutral" };
  }
  if (isLoanApplicationStatus(status)) {
    const m = STATUS_META[status];
    if (status === "UNDER_REPAYMENT") {
      const tone = getUnderRepaymentBadgeTone(options?.delinquency);
      return { label: m.label, intent: toneToIntent(tone) };
    }
    return { label: m.label, intent: m.intent };
  }
  if (isLoanAccountStatus(status)) {
    return ACCOUNT_STATUS_META[status];
  }
  return { label: String(status), intent: "neutral" };
}
