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
 * Map an API account-status string onto {@link AnyStatus} without folding
 * drift into a valid state.
 *
 * The account counterpart of `apiLoanStatus`. A value the frontend does not
 * know becomes `UNKNOWN:<raw>`, which {@link resolveStatusMeta} renders as
 * `Unknown (raw)` with a question-mark icon; handing the raw string to
 * `StatusBadge` instead labels the badge with the backend's own spelling,
 * which is the leak this exists to prevent.
 */
export function apiAccountStatus(raw: string | null | undefined): AnyStatus {
  const trimmed = raw?.trim() ?? "";
  return isLoanAccountStatus(trimmed) ? trimmed : `UNKNOWN:${trimmed}`;
}

/** Bank verdict on the latest disbursement attempt. */
const PROVIDER_DISBURSEMENT_LABELS: Record<string, string> = {
  SUCCESS: "Succeeded",
  PENDING: "Pending",
  FAILED: "Failed",
};

/**
 * Human label for a disbursement summary's `status`.
 *
 * One field, two vocabularies: `LspDisbursementSummarySupport.resolveStatus`
 * answers with the bank's verdict on the latest attempt when there is one, and
 * falls back to the loan account's own status when there is not. Account
 * statuses reuse {@link ACCOUNT_STATUS_META} rather than a second copy of those
 * labels, and anything unrecognised degrades to `Unknown (raw)`.
 */
export function disbursementStatusLabel(raw: string | null | undefined): string {
  const trimmed = raw?.trim() ?? "";
  const provider = PROVIDER_DISBURSEMENT_LABELS[trimmed];
  if (provider) return provider;
  if (isLoanAccountStatus(trimmed)) return ACCOUNT_STATUS_META[trimmed].label;
  return unknownLoanApplicationStatusLabel(trimmed);
}

/**
 * Gap #11: visual tone for loan-status badges. {@code UNDER_REPAYMENT} is
 * success when on-track, danger when delinquency aggregates are present.
 */
function getUnderRepaymentBadgeTone(delinquency?: StatusBadgeDelinquency): "success" | "danger" {
  const dpd = delinquency?.maxDaysPastDue ?? 0;
  const overdue = delinquency?.overdueInstallmentCount ?? 0;
  return dpd > 0 || overdue > 0 ? "danger" : "success";
}

/**
 * Label for a delinquent loan under repayment.
 *
 * The tone above flips `success → danger`, but tone is colour. DESIGN.md's
 * "Never Colour Alone" rule requires colour **plus icon plus text**, and names
 * `StatusBadge` as the component that enforces it — yet the label used to stay
 * "Under repayment" either way, so the badge's accessible name was identical
 * for a current loan and one 25 days past due. The days-past-due count is the
 * datum an operator or an LSP agent actually acts on, so it goes in the text.
 *
 * Falls back to the overdue-installment count when DPD is absent but the loan
 * is still delinquent, and to a bare "overdue" when neither figure is present.
 */
function getUnderRepaymentLabel(base: string, delinquency?: StatusBadgeDelinquency): string {
  const dpd = delinquency?.maxDaysPastDue ?? 0;
  const overdue = delinquency?.overdueInstallmentCount ?? 0;
  if (dpd > 0) {
    return `${base} · ${dpd}d past due`;
  }
  if (overdue > 0) {
    return `${base} · ${overdue} overdue`;
  }
  return base;
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
      return {
        label: getUnderRepaymentLabel(m.label, options?.delinquency),
        intent: toneToIntent(tone),
      };
    }
    return { label: m.label, intent: m.intent };
  }
  if (isLoanAccountStatus(status)) {
    return ACCOUNT_STATUS_META[status];
  }
  return { label: String(status), intent: "neutral" };
}
