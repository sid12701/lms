import { STATUS_META, type Intent } from "@/lib/lifecycle";
import {
  isLoanApplicationStatus,
  unknownLoanApplicationStatusLabel,
  type LoanStatusOrUnknown,
} from "@/lib/loan-application-status";
import type { LoanAccountStatus } from "@/types";

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
  ACTIVE: { label: "Active", intent: "success" },
  CLOSED: { label: "Closed", intent: "neutral" },
  FORECLOSED: { label: "Foreclosed", intent: "neutral" },
};

/**
 * Gap #11: visual tone for loan-status badges. {@code UNDER_REPAYMENT} is
 * success when on-track, danger when delinquency aggregates are present.
 */
function getStatusBadgeTone(
  status: LoanStatusOrUnknown,
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
      return "success";
    case "REJECTED":
    case "INVALID":
    case "DISBURSEMENT_RETRY":
      return "danger";
    default:
      return "neutral";
  }
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
