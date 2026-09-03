/**
 * Human labels for loan-application activity rows (ops Activity tab +
 * partner "Recent activity" card). Keeps enum drift visible as
 * `Unknown (raw)` per CONTEXT.md.
 */
import { STATUS_META } from "@/lib/lifecycle";
import {
  isLoanApplicationStatus,
  unknownLoanApplicationStatusLabel,
} from "@/lib/loan-application-status";

const ACTIVITY_TYPE_LABELS: Record<string, string> = {
  STATUS_TRANSITION: "Status transition",
  INTAKE_CAPTURED: "Intake captured",
  DOCUMENT_REVIEW_UPDATED: "Document review updated",
};

const STATUS_TRANSITION_SUMMARY_RE = /^Moved from (.+) to (.+)$/;

/** Canonical label for a loan-application status code (known or drift). */
export function loanStatusLabel(status: string | null | undefined): string {
  if (!status) return "—";
  if (isLoanApplicationStatus(status)) {
    return STATUS_META[status].label;
  }
  return unknownLoanApplicationStatusLabel(status);
}

/** Human label for `lastActivity.activityType` / audit stream codes. */
export function formatActivityType(activityType: string): string {
  const trimmed = activityType.trim();
  if (!trimmed) return "—";
  const known = ACTIVITY_TYPE_LABELS[trimmed];
  if (known) return known;
  return unknownLoanApplicationStatusLabel(trimmed);
}

/**
 * Human summary for `lastActivity.summary`. Status transitions from the
 * backend arrive as `Moved from INITIALIZED to UNDER_REPAYMENT`; ops Activity
 * renders `Initialized → Under repayment` instead.
 */
export function formatActivitySummary(summary: string, activityType?: string): string {
  const trimmed = summary.trim();
  if (!trimmed) return "—";

  if (activityType === "STATUS_TRANSITION") {
    const match = STATUS_TRANSITION_SUMMARY_RE.exec(trimmed);
    if (match) {
      return `${loanStatusLabel(match[1])} → ${loanStatusLabel(match[2])}`;
    }
  }

  return trimmed;
}
