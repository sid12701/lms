/**
 * UI-layer copy + tone catalog for lifecycle transitions.
 *
 * The *gate* (which transitions are allowed, by role + preconditions) lives
 * exclusively in `src/lib/lifecycle.ts` â€” `canTransition()` is the source of
 * truth and is byte-identical between the client and the backend.
 *
 * This file maps each (from, to) pair onto:
 *   - a UI label (verb)
 *   - a tone â€” `approve` / `default` / `destructive`
 *   - whether a free-text reason is required at the dialog
 *   - the coarse permission gate the host page should enforce in addition to
 *     the role check baked into `canTransition()`
 *
 * Authoring rule: every action's `(from, to)` MUST exist in `TRANSITIONS`
 * (i.e. `findRule()` returns a rule). The unit tests in `actions.test.ts`
 * assert this contract â€” it makes "the UI can offer a transition the gate
 * doesn't know about" impossible by construction.
 */
import type { LoanStatus, Permission } from "@/types";
import { TRANSITIONS, canTransition } from "@/lib/lifecycle";

export type LifecycleActionTone = "default" | "destructive" | "approve";

export interface LifecycleAction {
  /** Stable id, used as React key + analytics handle. */
  id: string;
  /** Verb shown on the action button. */
  label: string;
  /** Target status the rule transitions to. */
  toStatus: LoanStatus;
  tone: LifecycleActionTone;
  /** When true, the confirm dialog requires a non-empty reason. */
  requiresReason: boolean;
  /** Coarse permission gate the host page should consult. */
  permission: Permission;
}

// Re-export the gate so callers don't have to reach into `lib/lifecycle`
// for the precondition check while still using this UI layer.
export { canTransition };

/**
 * Per-target tone + reason copy. We keep this map tight: only the *meaningful*
 * surface that the UI actually exposes today. Anything missing here is
 * treated as a `default` tone with no reason required (see `actionFor`).
 */
const ACTION_OVERRIDES: Partial<
  Record<
    LoanStatus,
    {
      tone: LifecycleActionTone;
      requiresReason: boolean;
      permission: Permission;
      labelOverride?: string;
    }
  >
> = {
  // Approval / progress
  AWAITING_APPROVAL: {
    tone: "default",
    requiresReason: false,
    permission: "LOAN_STATUS_UPDATE",
  },
  APPROVED_PENDING_DISBURSAL: {
    tone: "approve",
    requiresReason: false,
    permission: "LOAN_STATUS_UPDATE",
  },
  DISBURSEMENT_IN_PROGRESS: {
    tone: "approve",
    requiresReason: false,
    permission: "DISBURSEMENT_TRIGGER",
  },
  // Foreclosure
  FORECLOSURE_REQUESTED: {
    tone: "default",
    requiresReason: true,
    permission: "FORECLOSURE_TRIGGER",
  },
  FORECLOSURE_APPROVED: {
    tone: "approve",
    requiresReason: true,
    permission: "FORECLOSURE_TRIGGER",
  },
  FORECLOSED: {
    tone: "approve",
    requiresReason: true,
    permission: "FORECLOSURE_TRIGGER",
  },
  // Destructive / failure
  REJECTED: {
    tone: "destructive",
    requiresReason: true,
    permission: "LOAN_STATUS_UPDATE",
  },
  CANCELLED: {
    tone: "destructive",
    requiresReason: true,
    permission: "LOAN_STATUS_UPDATE",
  },
  INVALIDATED: {
    tone: "destructive",
    requiresReason: true,
    permission: "LOAN_WRITE",
  },
};

/**
 * Default copy when a target status is not present in `ACTION_OVERRIDES`.
 * Origination + servicing transitions tend to be neutral progress steps.
 */
const DEFAULT_OVERRIDE: {
  tone: LifecycleActionTone;
  requiresReason: boolean;
  permission: Permission;
  labelOverride?: string;
} = {
  tone: "default",
  requiresReason: false,
  permission: "LOAN_STATUS_UPDATE",
};

function makeId(from: LoanStatus, to: LoanStatus): string {
  return `${from}__${to}`;
}

/**
 * UI catalog: every (from, to) pair the rule table exposes, projected
 * onto the LifecycleAction shape. Filtered to "human-actionable" rules â€”
 * SYSTEM-only transitions (allowedRoles is empty) are dropped because no
 * role would ever see a button for them.
 */
export const LIFECYCLE_ACTIONS: LifecycleAction[] = TRANSITIONS.filter(
  (rule) => rule.allowedRoles.length > 0,
).map((rule) => {
  const ov = ACTION_OVERRIDES[rule.to] ?? DEFAULT_OVERRIDE;
  // Destructive rule.intent should always end up tone="destructive" so the
  // table's destructive marker (e.g. "Cancel foreclosure") wins over the
  // per-target override.
  const tone: LifecycleActionTone = rule.intent === "destructive" ? "destructive" : ov.tone;
  return {
    id: makeId(rule.from, rule.to),
    label: ov.labelOverride ?? rule.label,
    toStatus: rule.to,
    tone,
    // Destructive transitions always require a reason so the audit log
    // captures intent, regardless of the per-target default.
    requiresReason: tone === "destructive" ? true : ov.requiresReason,
    permission: ov.permission,
  };
});

/**
 * Returns every UI-actionable transition leaving `from`, regardless of
 * role/precondition state. Callers (ActionBar) compute disabled-with-tooltip
 * by feeding each action through `canTransition()`.
 */
export function actionsFor(from: LoanStatus): LifecycleAction[] {
  return TRANSITIONS.filter((r) => r.from === from && r.allowedRoles.length > 0).map((rule) => {
    const ov = ACTION_OVERRIDES[rule.to] ?? DEFAULT_OVERRIDE;
    const tone: LifecycleActionTone = rule.intent === "destructive" ? "destructive" : ov.tone;
    return {
      id: makeId(rule.from, rule.to),
      label: ov.labelOverride ?? rule.label,
      toStatus: rule.to,
      tone,
      requiresReason: tone === "destructive" ? true : ov.requiresReason,
      permission: ov.permission,
    };
  });
}
