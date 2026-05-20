/**
 * Centralised lifecycle state machine.
 *
 * The same `canTransition()` function is consulted by:
 *   - the UI's ActionBar (renders allowed transitions only)
 *   - the mock router (enforces server-side validation)
 *
 * BR coverage:
 *   - BR-1  one-open-loan check       (precondition `borrowerHasOtherOpenLoan`)
 *   - BR-2  approval doc gate         (precondition `requiredForApproval` docs)
 *   - BR-3  disbursement doc gate     (precondition `requiredForDisbursement` docs)
 *   - BR-4  KYC required for approval (precondition `borrower.kycComplete`)
 *   - BR-6  illegal-transition reject (TRANSITIONS table is the allowed list)
 *   - BR-9  foreclosure-quote expiry  (precondition `foreclosureQuoteExpired`)
 *   - BR-10 schedule-required gate    (precondition `hasSchedule`)
 *   - BR-12 schedule-frozen gate      (precondition `scheduleFrozen` blocks replace)
 *   - BR-13 partial-payment reject    (helper `isBlockingTransition` documented)
 *   - BR-14 first-payment auto-advance (helper `postRepaymentAutoAdvance`)
 *   - BR-15 full-repayment closure    (helper `fullRepaymentClosure`)
 */
import type { Borrower, LoanApplication, LoanDocument, LoanStatus, Role } from "@/types";

// ─────────────────────────────────────────────────────────────────────────────
// Status meta + grouping (plan §5.5)
// ─────────────────────────────────────────────────────────────────────────────

export type Intent = "success" | "warning" | "danger" | "info" | "progress" | "neutral" | "revoked";

export type LifecycleGroup =
  | "ORIGINATION"
  | "UNDERWRITING"
  | "APPROVAL"
  | "DISBURSEMENT"
  | "SERVICING"
  | "DELINQUENCY"
  | "CLOSURE"
  | "FAILURE";

export interface StatusMeta {
  label: string;
  intent: Intent;
  group: LifecycleGroup;
  /** True if this status counts as an "open loan" for BR-1. */
  open: boolean;
}

export const STATUS_META: Record<LoanStatus, StatusMeta> = {
  // ORIGINATION
  INITIATED: { label: "Initiated", intent: "neutral", group: "ORIGINATION", open: true },
  KYC_PENDING: { label: "KYC pending", intent: "neutral", group: "ORIGINATION", open: true },
  DOCS_PENDING: { label: "Docs pending", intent: "neutral", group: "ORIGINATION", open: true },

  // UNDERWRITING
  UNDER_REVIEW: { label: "Under review", intent: "progress", group: "UNDERWRITING", open: true },
  AWAITING_APPROVAL: {
    label: "Awaiting approval",
    intent: "progress",
    group: "UNDERWRITING",
    open: true,
  },

  // APPROVAL
  APPROVED: { label: "Approved", intent: "progress", group: "APPROVAL", open: true },
  APPROVED_PENDING_DISBURSAL: {
    label: "Approved · pending disbursal",
    intent: "progress",
    group: "APPROVAL",
    open: true,
  },

  // DISBURSEMENT
  DISBURSEMENT_IN_PROGRESS: {
    label: "Disbursement in progress",
    intent: "warning",
    group: "DISBURSEMENT",
    open: true,
  },
  DISBURSED: { label: "Disbursed", intent: "success", group: "DISBURSEMENT", open: true },

  // SERVICING
  UNDER_REPAYMENT: { label: "Under repayment", intent: "success", group: "SERVICING", open: true },
  PARTIALLY_PAID: { label: "Partially paid", intent: "success", group: "SERVICING", open: true },

  // DELINQUENCY
  DELINQUENT: { label: "Delinquent", intent: "warning", group: "DELINQUENCY", open: true },

  // CLOSURE — terminal closures
  FORECLOSURE_REQUESTED: {
    label: "Foreclosure requested",
    intent: "warning",
    group: "DELINQUENCY",
    open: true,
  },
  FORECLOSURE_APPROVED: {
    label: "Foreclosure approved",
    intent: "warning",
    group: "DELINQUENCY",
    open: true,
  },
  FORECLOSED: { label: "Foreclosed", intent: "neutral", group: "CLOSURE", open: false },
  FULLY_REPAID: { label: "Fully repaid", intent: "neutral", group: "CLOSURE", open: false },
  CLOSED: { label: "Closed", intent: "neutral", group: "CLOSURE", open: false },

  // FAILURE
  REJECTED: { label: "Rejected", intent: "danger", group: "FAILURE", open: false },
  CANCELLED: { label: "Cancelled", intent: "danger", group: "FAILURE", open: false },
  INVALIDATED: { label: "Invalidated", intent: "revoked", group: "FAILURE", open: false },
};

// ─────────────────────────────────────────────────────────────────────────────
// Result type + business-rule errors
// ─────────────────────────────────────────────────────────────────────────────

export type Result<T, E> = { ok: true; value: T } | { ok: false; error: E };

export type BusinessRuleErrorCode =
  | "KYC_INCOMPLETE"
  | "DOCS_INCOMPLETE"
  | "SCHEDULE_MISSING"
  | "BORROWER_HAS_OPEN_LOAN"
  | "INVALID_TRANSITION"
  | "ROLE_NOT_ALLOWED"
  | "FORECLOSURE_QUOTE_EXPIRED"
  | "PARTIAL_INSTALLMENT_REJECTED"
  | "SCHEDULE_FROZEN"
  | "SCHEDULE_RECONCILIATION_FAILED";

export class BusinessRuleError extends Error {
  public readonly code: BusinessRuleErrorCode;

  constructor(code: BusinessRuleErrorCode, message: string) {
    super(message);
    this.code = code;
    this.name = "BusinessRuleError";
  }
}

const ok = (): Result<void, BusinessRuleError> => ({ ok: true, value: undefined });
const err = (code: BusinessRuleErrorCode, message: string): Result<void, BusinessRuleError> => ({
  ok: false,
  error: new BusinessRuleError(code, message),
});

// ─────────────────────────────────────────────────────────────────────────────
// Transition context + rule
// ─────────────────────────────────────────────────────────────────────────────

export interface TransitionCtx {
  borrower?: Borrower;
  application?: LoanApplication;
  documents?: LoanDocument[];
  hasSchedule?: boolean;
  scheduleFrozen?: boolean;
  hasPostedRepayment?: boolean;
  borrowerHasOtherOpenLoan?: boolean;
  foreclosureQuoteExpired?: boolean;
}

export interface TransitionRule {
  from: LoanStatus;
  to: LoanStatus;
  /** Empty array == system-only; UI never offers it. */
  allowedRoles: Role[];
  preconditions?: (ctx: TransitionCtx) => Result<void, BusinessRuleError>;
  /** Verb shown on the action button. */
  label: string;
  intent: "primary" | "secondary" | "destructive";
}

// ─────────────────────────────────────────────────────────────────────────────
// Precondition helpers
// ─────────────────────────────────────────────────────────────────────────────

/** BR-4: borrower must have completed KYC. */
function requireKyc(ctx: TransitionCtx): Result<void, BusinessRuleError> {
  if (ctx.borrower && !ctx.borrower.kycComplete) {
    return err("KYC_INCOMPLETE", "KYC not complete for borrower");
  }
  return ok();
}

/** BR-1: borrower may not hold another open loan. */
function requireNoOtherOpenLoan(ctx: TransitionCtx): Result<void, BusinessRuleError> {
  if (ctx.borrowerHasOtherOpenLoan) {
    return err("BORROWER_HAS_OPEN_LOAN", "borrower already has an open loan (BR-1)");
  }
  return ok();
}

/** BR-2: every required-for-approval document must be VERIFIED. */
function requireApprovalDocs(ctx: TransitionCtx): Result<void, BusinessRuleError> {
  const docs = ctx.documents ?? [];
  const missing = docs.filter((d) => d.requiredForApproval && d.status !== "VERIFIED");
  if (missing.length > 0) {
    return err("DOCS_INCOMPLETE", `${missing.length} approval document(s) not verified`);
  }
  return ok();
}

/** BR-3: every required-for-disbursement document must be VERIFIED. */
function requireDisbursementDocs(ctx: TransitionCtx): Result<void, BusinessRuleError> {
  const docs = ctx.documents ?? [];
  const missing = docs.filter((d) => d.requiredForDisbursement && d.status !== "VERIFIED");
  if (missing.length > 0) {
    return err("DOCS_INCOMPLETE", `${missing.length} disbursement document(s) not verified`);
  }
  return ok();
}

/** BR-10: a repayment schedule must exist before disbursement. */
function requireSchedule(ctx: TransitionCtx): Result<void, BusinessRuleError> {
  if (!ctx.hasSchedule) {
    return err("SCHEDULE_MISSING", "no repayment schedule attached (BR-10)");
  }
  return ok();
}

/** BR-9: foreclosure quote must not be expired. */
function requireFreshForeclosureQuote(ctx: TransitionCtx): Result<void, BusinessRuleError> {
  if (ctx.foreclosureQuoteExpired) {
    return err("FORECLOSURE_QUOTE_EXPIRED", "foreclosure quote expired (BR-9)");
  }
  return ok();
}

function chain(
  ...checks: Array<(ctx: TransitionCtx) => Result<void, BusinessRuleError>>
): (ctx: TransitionCtx) => Result<void, BusinessRuleError> {
  return (ctx) => {
    for (const check of checks) {
      const r = check(ctx);
      if (!r.ok) return r;
    }
    return ok();
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Transition table
// ─────────────────────────────────────────────────────────────────────────────

const INTERNAL_OPS: Role[] = ["SYSTEM_ADMIN", "OPS_USER"];
const SYS_ADMIN: Role[] = ["SYSTEM_ADMIN"];
const SYSTEM_ONLY: Role[] = []; // auto-advance — no UI button

/** Statuses from which `INVALIDATED` is reachable (LSP_UI_WRITE on own tenant). */
const INVALIDATABLE_FROM: LoanStatus[] = [
  "INITIATED",
  "KYC_PENDING",
  "DOCS_PENDING",
  "UNDER_REVIEW",
  "AWAITING_APPROVAL",
  "APPROVED",
  "APPROVED_PENDING_DISBURSAL",
];

/** Statuses from which `CANCELLED` is reachable by SYSTEM_ADMIN. */
const CANCELLABLE_FROM: LoanStatus[] = [
  "INITIATED",
  "KYC_PENDING",
  "DOCS_PENDING",
  "UNDER_REVIEW",
  "AWAITING_APPROVAL",
];

export const TRANSITIONS: TransitionRule[] = [
  // Origination → underwriting
  {
    from: "INITIATED",
    to: "KYC_PENDING",
    allowedRoles: INTERNAL_OPS,
    label: "Mark KYC pending",
    intent: "secondary",
  },
  {
    from: "KYC_PENDING",
    to: "DOCS_PENDING",
    allowedRoles: INTERNAL_OPS,
    label: "Request documents",
    intent: "secondary",
  },
  {
    from: "DOCS_PENDING",
    to: "UNDER_REVIEW",
    allowedRoles: INTERNAL_OPS,
    label: "Send to review",
    intent: "secondary",
  },
  {
    from: "INITIATED",
    to: "AWAITING_APPROVAL",
    allowedRoles: INTERNAL_OPS,
    label: "Submit for review",
    intent: "primary",
    // BR-4 KYC required + BR-1 one-open-loan check at intake gate
    preconditions: chain(requireKyc, requireNoOtherOpenLoan),
  },
  {
    from: "UNDER_REVIEW",
    to: "AWAITING_APPROVAL",
    allowedRoles: INTERNAL_OPS,
    label: "Send to approver",
    intent: "primary",
    preconditions: requireKyc,
  },

  // Approval
  {
    from: "AWAITING_APPROVAL",
    to: "APPROVED_PENDING_DISBURSAL",
    allowedRoles: SYS_ADMIN,
    label: "Approve",
    intent: "primary",
    // BR-2 + BR-4 + BR-1
    preconditions: chain(requireKyc, requireApprovalDocs, requireNoOtherOpenLoan),
  },
  {
    from: "AWAITING_APPROVAL",
    to: "REJECTED",
    allowedRoles: SYS_ADMIN,
    label: "Reject",
    intent: "destructive",
  },

  // Disbursement
  {
    from: "APPROVED_PENDING_DISBURSAL",
    to: "DISBURSEMENT_IN_PROGRESS",
    allowedRoles: SYS_ADMIN,
    label: "Initiate disbursement",
    intent: "primary",
    // BR-3 + BR-10
    preconditions: chain(requireDisbursementDocs, requireSchedule),
  },
  {
    from: "DISBURSEMENT_IN_PROGRESS",
    to: "DISBURSED",
    allowedRoles: SYSTEM_ONLY, // mock-adapter callback
    label: "Confirm disbursed (system)",
    intent: "primary",
  },
  {
    from: "DISBURSEMENT_IN_PROGRESS",
    to: "APPROVED_PENDING_DISBURSAL",
    allowedRoles: SYS_ADMIN,
    label: "Mark disbursement failed",
    intent: "destructive",
  },

  // Servicing — BR-14 (auto)
  {
    from: "DISBURSED",
    to: "UNDER_REPAYMENT",
    allowedRoles: SYSTEM_ONLY, // BR-14: auto on first posted payment
    label: "Auto-advance on first payment (system)",
    intent: "primary",
  },
  {
    from: "UNDER_REPAYMENT",
    to: "PARTIALLY_PAID",
    allowedRoles: SYSTEM_ONLY,
    label: "Mark partially paid (system)",
    intent: "primary",
  },
  {
    from: "PARTIALLY_PAID",
    to: "UNDER_REPAYMENT",
    allowedRoles: SYSTEM_ONLY,
    label: "Resume repayment (system)",
    intent: "primary",
  },

  // Delinquency
  {
    from: "UNDER_REPAYMENT",
    to: "DELINQUENT",
    allowedRoles: SYSTEM_ONLY,
    label: "Flag delinquent (system)",
    intent: "destructive",
  },
  {
    from: "PARTIALLY_PAID",
    to: "DELINQUENT",
    allowedRoles: SYSTEM_ONLY,
    label: "Flag delinquent (system)",
    intent: "destructive",
  },
  {
    from: "DELINQUENT",
    to: "UNDER_REPAYMENT",
    allowedRoles: SYSTEM_ONLY,
    label: "Clear delinquency (system)",
    intent: "primary",
  },

  // Foreclosure
  {
    from: "UNDER_REPAYMENT",
    to: "FORECLOSURE_REQUESTED",
    allowedRoles: INTERNAL_OPS,
    label: "Request foreclosure",
    intent: "secondary",
  },
  {
    from: "PARTIALLY_PAID",
    to: "FORECLOSURE_REQUESTED",
    allowedRoles: INTERNAL_OPS,
    label: "Request foreclosure",
    intent: "secondary",
  },
  {
    from: "DELINQUENT",
    to: "FORECLOSURE_REQUESTED",
    allowedRoles: INTERNAL_OPS,
    label: "Request foreclosure",
    intent: "secondary",
  },
  {
    from: "FORECLOSURE_REQUESTED",
    to: "FORECLOSURE_APPROVED",
    allowedRoles: SYS_ADMIN,
    label: "Approve foreclosure",
    intent: "primary",
    preconditions: requireFreshForeclosureQuote,
  },
  {
    from: "FORECLOSURE_REQUESTED",
    to: "UNDER_REPAYMENT",
    allowedRoles: SYS_ADMIN,
    label: "Cancel foreclosure",
    intent: "destructive",
  },
  {
    from: "FORECLOSURE_APPROVED",
    to: "FORECLOSED",
    allowedRoles: SYS_ADMIN,
    label: "Settle foreclosure",
    intent: "primary",
  },

  // Closure paths
  {
    from: "UNDER_REPAYMENT",
    to: "FULLY_REPAID",
    allowedRoles: SYSTEM_ONLY, // BR-15
    label: "Auto-close on full repayment (system)",
    intent: "primary",
  },
  {
    from: "PARTIALLY_PAID",
    to: "FULLY_REPAID",
    allowedRoles: SYSTEM_ONLY, // BR-15
    label: "Auto-close on full repayment (system)",
    intent: "primary",
  },
  {
    from: "FULLY_REPAID",
    to: "CLOSED",
    allowedRoles: SYSTEM_ONLY,
    label: "Close account (system)",
    intent: "primary",
  },
  {
    from: "FORECLOSED",
    to: "CLOSED",
    allowedRoles: SYSTEM_ONLY,
    label: "Close account (system)",
    intent: "primary",
  },
];

// Cancellation: SYSTEM_ADMIN can cancel from early statuses.
for (const from of CANCELLABLE_FROM) {
  TRANSITIONS.push({
    from,
    to: "CANCELLED",
    allowedRoles: SYS_ADMIN,
    label: "Cancel application",
    intent: "destructive",
  });
}

// Invalidation: LSP_UI_WRITE for own-tenant; SYSTEM_ADMIN for any.
for (const from of INVALIDATABLE_FROM) {
  TRANSITIONS.push({
    from,
    to: "INVALIDATED",
    allowedRoles: ["LSP_UI_WRITE", "SYSTEM_ADMIN"],
    label: "Mark invalid",
    intent: "destructive",
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// canTransition + getNextActions
// ─────────────────────────────────────────────────────────────────────────────

function findRule(from: LoanStatus, to: LoanStatus): TransitionRule | undefined {
  return TRANSITIONS.find((r) => r.from === from && r.to === to);
}

/**
 * Validate a status transition. Same function the mock router uses on
 * the server side — UI gating + server enforcement are byte-identical.
 */
export function canTransition(
  role: Role,
  from: LoanStatus,
  to: LoanStatus,
  ctx: TransitionCtx,
): Result<void, BusinessRuleError> {
  const rule = findRule(from, to);
  if (!rule) {
    return err("INVALID_TRANSITION", `no rule from ${from} to ${to} (BR-6)`);
  }
  if (!rule.allowedRoles.includes(role)) {
    return err("ROLE_NOT_ALLOWED", `role ${role} cannot transition ${from} → ${to}`);
  }
  if (rule.preconditions) {
    return rule.preconditions(ctx);
  }
  return ok();
}

/**
 * Returns transitions the given role can currently take from `status`,
 * filtered by satisfied preconditions. Failed-precondition rules are
 * surfaced separately by the UI as disabled-with-tooltip; the orchestrator
 * decided to expose only "currently-takeable" actions here — the UI layer
 * may call `canTransition()` per candidate to render the disabled state.
 */
export function getNextActions(
  status: LoanStatus,
  role: Role,
  ctx: TransitionCtx,
): TransitionRule[] {
  return TRANSITIONS.filter((rule) => {
    if (rule.from !== status) return false;
    if (!rule.allowedRoles.includes(role)) return false;
    if (rule.preconditions) {
      const r = rule.preconditions(ctx);
      if (!r.ok) return false;
    }
    return true;
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// Mock-router helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * "Blocking" transitions are those the UI must NEVER fire optimistically
 * (status changes + money movements). All TRANSITIONS in this file count
 * as blocking — kept as a function so future read-only events can be added.
 */
export function isBlockingTransition(_rule: TransitionRule): boolean {
  return true;
}

/**
 * BR-14: on the FIRST posted repayment, advance DISBURSED → UNDER_REPAYMENT.
 * Returns null when no auto-advance applies.
 */
export function postRepaymentAutoAdvance(currentStatus: LoanStatus): LoanStatus | null {
  return currentStatus === "DISBURSED" ? "UNDER_REPAYMENT" : null;
}

/**
 * BR-15: when a loan is fully repaid, account closes with reason `FULLY_REPAID`
 * and a `loan.repayment.posted` webhook is enqueued.
 */
export function fullRepaymentClosure(): {
  newStatus: LoanStatus;
  closureReason: "FULLY_REPAID";
  webhookEvent: "loan.repayment.posted";
} {
  return {
    newStatus: "FULLY_REPAID",
    closureReason: "FULLY_REPAID",
    webhookEvent: "loan.repayment.posted",
  };
}
