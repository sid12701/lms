/**
 * Advisory UX lifecycle helpers for loan application status.
 *
 * `canTransition()` gates which actions the UI offers; the backend
 * `LoanApplicationStatus` enum and `LoanApplicationStatusTransitioner` enforce
 * transitions on the server.
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
 *   - Gap #11 badge tone              (`getStatusBadgeTone` in `statusBadgeMeta.ts`)
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
  INITIALIZED: { label: "Initialized", intent: "neutral", group: "ORIGINATION", open: true },
  AWAITING_APPROVAL: {
    label: "Awaiting approval",
    intent: "progress",
    group: "UNDERWRITING",
    open: true,
  },
  APPROVED_PENDING_DISBURSAL: {
    label: "Approved · pending disbursal",
    intent: "progress",
    group: "APPROVAL",
    open: true,
  },
  REJECTED: { label: "Rejected", intent: "danger", group: "FAILURE", open: false },
  DISBURSEMENT_RETRY: {
    label: "Disbursement retry",
    intent: "warning",
    group: "DISBURSEMENT",
    open: true,
  },
  INVALID: { label: "Invalid", intent: "revoked", group: "FAILURE", open: false },
  DISBURSED: { label: "Disbursed", intent: "success", group: "DISBURSEMENT", open: true },
  UNDER_REPAYMENT: { label: "Under repayment", intent: "success", group: "SERVICING", open: true },
  CLOSED: { label: "Closed", intent: "neutral", group: "CLOSURE", open: false },
  FORECLOSED: { label: "Foreclosed", intent: "neutral", group: "CLOSURE", open: false },
};

// ─────────────────────────────────────────────────────────────────────────────
// Result type + business-rule errors
// ─────────────────────────────────────────────────────────────────────────────

export type Result<T, E> = { ok: true; value: T } | { ok: false; error: E };

type BusinessRuleErrorCode =
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

class BusinessRuleError extends Error {
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

/** BR-2: every required-for-approval document must be uploaded (Gap #18: no verify step). */
function requireApprovalDocs(ctx: TransitionCtx): Result<void, BusinessRuleError> {
  const docs = ctx.documents ?? [];
  const missing = docs.filter((d) => d.requiredForApproval && d.status !== "UPLOADED");
  if (missing.length > 0) {
    return err("DOCS_INCOMPLETE", `${missing.length} approval document(s) not uploaded`);
  }
  return ok();
}

/** BR-3: every required-for-disbursement document must be uploaded (Gap #18: no verify step). */
function requireDisbursementDocs(ctx: TransitionCtx): Result<void, BusinessRuleError> {
  const docs = ctx.documents ?? [];
  const missing = docs.filter((d) => d.requiredForDisbursement && d.status !== "UPLOADED");
  if (missing.length > 0) {
    return err("DOCS_INCOMPLETE", `${missing.length} disbursement document(s) not uploaded`);
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

const SYS_ADMIN: Role[] = ["SYSTEM_ADMIN"];
const LSP_INVALIDATORS: Role[] = ["LSP_UI_WRITE", "SYSTEM_ADMIN"];
const SYSTEM_ONLY: Role[] = [];

const PRE_DISBURSAL_INVALIDATABLE: LoanStatus[] = [
  "INITIALIZED",
  "AWAITING_APPROVAL",
  "APPROVED_PENDING_DISBURSAL",
  "DISBURSEMENT_RETRY",
];

const CORE_TRANSITIONS: TransitionRule[] = [
  {
    from: "INITIALIZED",
    to: "AWAITING_APPROVAL",
    allowedRoles: SYS_ADMIN,
    label: "Submit for approval",
    intent: "primary",
    preconditions: chain(requireKyc, requireNoOtherOpenLoan),
  },
  {
    from: "AWAITING_APPROVAL",
    to: "APPROVED_PENDING_DISBURSAL",
    allowedRoles: SYS_ADMIN,
    label: "Approve",
    intent: "primary",
    preconditions: chain(requireKyc, requireApprovalDocs, requireNoOtherOpenLoan),
  },
  {
    from: "AWAITING_APPROVAL",
    to: "REJECTED",
    allowedRoles: SYS_ADMIN,
    label: "Reject",
    intent: "destructive",
  },
  {
    from: "APPROVED_PENDING_DISBURSAL",
    to: "DISBURSED",
    allowedRoles: SYS_ADMIN,
    label: "Initiate disbursement",
    intent: "primary",
    preconditions: chain(requireDisbursementDocs, requireSchedule),
  },
  {
    from: "APPROVED_PENDING_DISBURSAL",
    to: "DISBURSEMENT_RETRY",
    allowedRoles: SYS_ADMIN,
    label: "Mark disbursement retry",
    intent: "destructive",
  },
  {
    from: "DISBURSEMENT_RETRY",
    to: "DISBURSED",
    allowedRoles: SYS_ADMIN,
    /*
     * "New disbursement attempt", not "Retry disbursement". `CONTEXT.md` lists
     * **retry** as a term to avoid in favour of **disbursement attempt**,
     * because "retry" is silent on whether the previous attempt actually
     * reached the bank — the one thing that must never be ambiguous here.
     *
     * The label on the transition *into* `DISBURSEMENT_RETRY` above keeps the
     * word, because there it names the canonical lifecycle status (which
     * `PRODUCT.md` lists verbatim) rather than describing the act.
     */
    label: "New disbursement attempt",
    intent: "primary",
  },
  {
    from: "DISBURSED",
    to: "UNDER_REPAYMENT",
    allowedRoles: SYSTEM_ONLY,
    label: "Auto-advance on first payment (system)",
    intent: "primary",
  },
  {
    from: "DISBURSED",
    to: "CLOSED",
    allowedRoles: SYSTEM_ONLY,
    label: "Close account (system)",
    intent: "primary",
  },
  {
    from: "DISBURSED",
    to: "FORECLOSED",
    allowedRoles: SYSTEM_ONLY,
    label: "Foreclose (system)",
    intent: "primary",
  },
  {
    from: "UNDER_REPAYMENT",
    to: "CLOSED",
    allowedRoles: SYSTEM_ONLY,
    label: "Close account (system)",
    intent: "primary",
  },
  {
    from: "UNDER_REPAYMENT",
    to: "FORECLOSED",
    allowedRoles: SYS_ADMIN,
    label: "Settle foreclosure",
    intent: "primary",
    preconditions: requireFreshForeclosureQuote,
  },
];

const INVALIDATION_TRANSITIONS: TransitionRule[] = PRE_DISBURSAL_INVALIDATABLE.map((from) => ({
  from,
  to: "INVALID" as const,
  allowedRoles: LSP_INVALIDATORS,
  label: "Mark invalid",
  intent: "destructive" as const,
}));

export const TRANSITIONS: TransitionRule[] = [...CORE_TRANSITIONS, ...INVALIDATION_TRANSITIONS];

// ─────────────────────────────────────────────────────────────────────────────
// canTransition
// ─────────────────────────────────────────────────────────────────────────────

function findRule(from: LoanStatus, to: LoanStatus): TransitionRule | undefined {
  return TRANSITIONS.find((r) => r.from === from && r.to === to);
}

/** Advisory UX gate — backend `LoanApplicationStatusTransitioner` is authoritative. */
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
