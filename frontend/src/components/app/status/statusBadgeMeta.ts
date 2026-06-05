import { STATUS_META, type Intent } from "@/lib/lifecycle";
import type { LoanAccountStatus, LoanStatus } from "@/types";

export type AnyStatus = LoanStatus | LoanAccountStatus;

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
  ACTIVE: { label: "Active", intent: "success" },
  CLOSED: { label: "Closed", intent: "neutral" },
  FORECLOSED: { label: "Foreclosed", intent: "neutral" },
};

/**
 * Gap #11: visual tone for loan-status badges. {@code UNDER_REPAYMENT} is
 * success when on-track, danger when delinquency aggregates are present.
 */
function getStatusBadgeTone(
  status: LoanStatus,
  delinquency?: StatusBadgeDelinquency,
): "success" | "danger" | "neutral" {
  if (status === "UNDER_REPAYMENT") {
    const dpd = delinquency?.maxDaysPastDue ?? 0;
    const overdue = delinquency?.overdueInstallmentCount ?? 0;
    return dpd > 0 || overdue > 0 ? "danger" : "success";
  }
  switch (status) {
    case "APPROVED_PENDING_DISBURSAL":
    case "DISBURSED":
    case "CLOSED":
    case "FULLY_REPAID":
      return "success";
    case "REJECTED":
    case "INVALID":
    case "INVALIDATED":
    case "CANCELLED":
    case "DISBURSEMENT_RETRY":
    case "DELINQUENT":
      return "danger";
    default:
      return "neutral";
  }
}

function toneToIntent(tone: "success" | "danger" | "neutral"): Intent {
  return tone;
}

/** Lookup that succeeds for either enum. Always returns a non-empty meta. */
export function resolveStatusMeta(
  status: AnyStatus,
  options?: { delinquency?: StatusBadgeDelinquency },
): ResolvedStatusMeta {
  if (status in STATUS_META) {
    const m = STATUS_META[status as LoanStatus];
    if (status === "UNDER_REPAYMENT") {
      const tone = getStatusBadgeTone(status, options?.delinquency);
      return { label: m.label, intent: toneToIntent(tone) };
    }
    return { label: m.label, intent: m.intent };
  }
  if (status in ACCOUNT_STATUS_META) {
    return ACCOUNT_STATUS_META[status as LoanAccountStatus];
  }
  return { label: String(status), intent: "neutral" };
}
