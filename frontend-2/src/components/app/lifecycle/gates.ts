/**
 * UI-side gate computation for lifecycle transitions.
 *
 * The authoritative precondition logic lives in `src/lib/lifecycle.ts`
 * (`canTransition`, the `preconditions` chain on each rule). Those checks run
 * with the full backend `TransitionCtx` (loan, docs[], schedule, repayments…)
 * which the UI layer does not assemble synchronously.
 *
 * To still surface "disabled with reason" tooltips on action buttons, the host
 * page evaluates a small number of business gates (BR-3 docs-complete, BR-10
 * schedule-valid) and feeds the booleans here. We then map (currentStatus, role,
 * gates) → a human-readable reason string, or null if the transition is
 * actionable.
 */
import { TRANSITIONS } from "@/lib/lifecycle";
import { hasPermission } from "@/lib/permissions";
import type { LoanStatus, Role } from "@/types";
import type { LifecycleAction } from "./actions";

export interface TransitionGates {
  /** BR-3 — every required-for-disbursement document is uploaded. */
  docsComplete?: boolean;
  /** BR-10 — a valid repayment schedule exists for the loan. */
  scheduleValid?: boolean;
}

/**
 * Targets whose backing transition rule chains through a docs-precondition
 * (`requireApprovalDocs` for AWAITING_APPROVAL → APPROVED_PENDING_DISBURSAL
 * — BR-4, and `requireDisbursementDocs` for the disbursement step — BR-3).
 */
const DOCS_GATED_TARGETS: ReadonlySet<LoanStatus> = new Set([
  "APPROVED_PENDING_DISBURSAL",
  "DISBURSEMENT_IN_PROGRESS",
]);

/** Schedule gate only applies to the disbursement step (BR-10). */
const SCHEDULE_GATED_TARGETS: ReadonlySet<LoanStatus> = new Set([
  "DISBURSEMENT_IN_PROGRESS",
]);

export function resolveDisabledReason(
  action: LifecycleAction,
  currentStatus: LoanStatus,
  role: Role,
  gates?: TransitionGates,
): string | null {
  if (currentStatus === action.toStatus) {
    return "Already in this status.";
  }
  if (!hasPermission(role, action.permission)) {
    return "Insufficient permissions.";
  }
  const rule = TRANSITIONS.find(
    (r) => r.from === currentStatus && r.to === action.toStatus,
  );
  if (!rule) {
    return "Transition not allowed from the current status.";
  }
  if (DOCS_GATED_TARGETS.has(action.toStatus) && gates?.docsComplete === false) {
    return "Documents incomplete (BR-3).";
  }
  if (SCHEDULE_GATED_TARGETS.has(action.toStatus) && gates?.scheduleValid === false) {
    return "Repayment schedule must be present and valid (BR-10).";
  }
  return null;
}
