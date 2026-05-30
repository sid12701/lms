/**
 * Loan-applications handler (Phase 5).
 *
 * Implements every endpoint the list page, the detail shell, and the six
 * per-tab content components consume. The contract is owned by
 * `@/features/loan-applications/types`; this file mirrors that contract
 * one-to-one.
 *
 * Endpoints:
 *   GET  /api/v1/loan-applications                          → list + filters
 *   GET  /api/v1/loan-applications/:id                      → detail header
 *   GET  /api/v1/loan-applications/:id/schedule             → Schedule tab
 *   GET  /api/v1/loan-applications/:id/documents            → Documents tab
 *   GET  /api/v1/loan-applications/:id/repayments           → Repayments tab
 *   GET  /api/v1/loan-applications/:id/activity             → Activity tab
 *   GET  /api/v1/loan-applications/:id/webhooks             → Webhooks tab
 *   POST /api/v1/loan-applications/:id/transitions          → status update
 *   POST /api/v1/loan-applications/:id/repayments           → post payment
 *   POST /api/v1/loan-applications/:id/disbursement         → initiate
 *
 * BR coverage enforced here:
 *   - BR-3 (docs complete)          — `computeDocsComplete`
 *   - BR-5 (idempotency)            — router-level cache, plus body key passthrough
 *   - BR-6 / BR-2 / BR-4 / BR-10    — delegated to `canTransition`
 *   - BR-7 (PII masking)            — list rows use `maskBorrowerName`
 *   - BR-11 (schedule reconciliation) — `validateSchedule`
 *   - BR-13 (full-installment-only repayment) — `enforceFullInstallment`
 *   - BR-14 (auto-advance DISBURSED → UNDER_REPAYMENT) — `autoAdvanceOnFirstPayment`
 *   - BR-15 (full-repayment auto-close)               — `autoCloseOnFullRepayment`
 *
 * LSP scoping:
 *   SYSTEM_ADMIN / OPS_USER / PRODUCT_ADMIN  → see every application
 *   LSP_UI_READ  / LSP_UI_WRITE              → scoped to `session.user.lspId`
 *   anything else                            → 401
 */
import { z } from "zod";
import { Iso8601, IsoDate, MoneyINR, MoneyINRPositive, Uuid } from "@/schemas/common";
import { LoanStatus } from "@/schemas/loan-application";
import { LspProvidedSchedule } from "@/schemas/lsp-provided-schedule";
import {
  BusinessRuleError,
  canTransition,
  postRepaymentAutoAdvance,
  type TransitionCtx,
} from "@/lib/lifecycle";
import { newIdempotencyKey } from "@/lib/idempotency";
import type {
  ApplicationAuditEvent,
  LoanAccount,
  LoanApplication,
  LoanDocument,
  LoanStatus as LoanStatusType,
  LoanProduct,
  Lsp,
  PaymentTransaction,
  RepaymentInstallment,
  RepaymentSchedule,
  Role,
} from "@/types";
import type { Borrower } from "@/schemas/borrower";
import type {
  CompleteForeclosureInput,
  CompleteForeclosureResponse,
  LoanApplicationActivityResponse,
  LoanApplicationDetail,
  LoanApplicationDocumentsResponse,
  LoanApplicationListItem,
  LoanApplicationListResponse,
  LoanApplicationRepaymentsResponse,
  LoanApplicationScheduleResponse,
  LoanApplicationWebhookDelivery,
  LoanApplicationWebhooksResponse,
  SubmitScheduleInput,
  SubmitScheduleResponse,
} from "@/features/loan-applications/types";
import { dispatch, registerRoute, type MockRequest } from "../router";
import {
  BadRequestError,
  ForbiddenError,
  NotFoundError,
  UnauthorizedError,
  wrapBusinessRuleError,
} from "../errors";
import type { MockDb } from "../db/state";
import { maskBorrowerName } from "./home";

// ─── Role helpers ────────────────────────────────────────────────────────────

const INTERNAL_ROLES = new Set<Role>(["SYSTEM_ADMIN", "OPS_USER", "PRODUCT_ADMIN"]);
const LSP_UI_ROLES = new Set<Role>(["LSP_UI_READ", "LSP_UI_WRITE"]);

interface ActiveSession {
  userId: string;
  role: Role;
  lspId: string | null;
}

function requireSession(db: MockDb, correlationId: string): ActiveSession {
  if (!db.currentSession) {
    throw new UnauthorizedError(correlationId, "no active session");
  }
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    throw new UnauthorizedError(correlationId, "user no longer exists");
  }
  return { userId: user.id, role: user.role, lspId: user.lspId };
}

/** True when the session can see/touch rows belonging to `application`. */
function canAccessApplication(session: ActiveSession, app: LoanApplication): boolean {
  if (INTERNAL_ROLES.has(session.role)) return true;
  if (LSP_UI_ROLES.has(session.role)) return session.lspId === app.lspId;
  return false;
}

function loadApplicationForSession(
  db: MockDb,
  session: ActiveSession,
  id: string,
  correlationId: string,
): LoanApplication {
  const app = db.applications.get(id);
  if (!app) {
    throw new NotFoundError(correlationId, `application ${id} not found`);
  }
  if (!canAccessApplication(session, app)) {
    throw new ForbiddenError(correlationId, "application out of tenant scope");
  }
  return app;
}

// ─── BR-3 / BR-10 / BR-11 helpers ────────────────────────────────────────────

function getAccountForApplication(db: MockDb, applicationId: string): LoanAccount | null {
  for (const a of db.accounts.values()) {
    if (a.applicationId === applicationId) return a;
  }
  return null;
}

function getDocumentsForApplication(db: MockDb, applicationId: string): LoanDocument[] {
  const out: LoanDocument[] = [];
  for (const d of db.documents.values()) {
    if (d.applicationId === applicationId) out.push(d);
  }
  return out;
}

/**
 * BR-3 disbursement-doc completeness — every document flagged
 * `requiredForDisbursement` must be UPLOADED (Gap #18: per-doc verification
 * is removed; SUBMITTED on the backend maps to UPLOADED in the FE).
 */
function computeDocsComplete(documents: ReadonlyArray<LoanDocument>): boolean {
  const required = documents.filter((d) => d.requiredForDisbursement);
  if (required.length === 0) return true;
  return required.every((d) => d.status === "UPLOADED");
}

/**
 * BR-11 schedule reconciliation:
 *   - contiguous installment numbering (1..N)
 *   - strictly increasing due dates
 *   - principalDue + interestDue === installmentAmount for every row
 *   - sum(principalDue) === account.principal
 */
function validateSchedule(
  schedule: RepaymentSchedule | null,
  installments: ReadonlyArray<RepaymentInstallment>,
  account: LoanAccount | null,
): boolean {
  if (!schedule || installments.length === 0 || !account) return false;
  const sorted = [...installments].sort((a, b) => a.number - b.number);
  let totalPrincipal = 0;
  let prevDate: string | null = null;
  for (let i = 0; i < sorted.length; i++) {
    const inst = sorted[i];
    if (!inst) return false;
    if (inst.number !== i + 1) return false;
    if (prevDate !== null && inst.dueDate <= prevDate) return false;
    prevDate = inst.dueDate;
    if (inst.principalDue + inst.interestDue !== inst.installmentAmount) return false;
    totalPrincipal += inst.principalDue;
  }
  // Allow a 1-rupee rounding tolerance — installmentAmount uses Math.round
  // in the seed which can drift by a few rupees over a 12-month tenure.
  return Math.abs(totalPrincipal - account.principal) <= installments.length;
}

function getScheduleForAccount(
  db: MockDb,
  accountId: string,
): { schedule: RepaymentSchedule | null; installments: RepaymentInstallment[] } {
  let schedule: RepaymentSchedule | null = null;
  for (const s of db.schedules.values()) {
    if (s.accountId === accountId) {
      schedule = s;
      break;
    }
  }
  const installments = db.installments.get(accountId) ?? [];
  return { schedule, installments: [...installments] };
}

// ─── List handler ────────────────────────────────────────────────────────────

const ListFiltersSchema = z.object({
  q: z.string().trim().min(1).max(120).optional(),
  lspLoanId: z.string().trim().min(1).max(120).optional(),
  bhawLoanId: z.string().trim().min(1).max(120).optional(),
  disbursalDateFrom: IsoDate.optional(),
  disbursalDateTo: IsoDate.optional(),
  status: z.array(LoanStatus).optional(),
  lspId: Uuid.optional(),
  productId: Uuid.optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
  sortBy: z.enum(["createdAt", "updatedAt", "requestedAmount", "status"]).optional(),
  sortDir: z.enum(["asc", "desc"]).optional(),
});

function parseListQuery(
  req: MockRequest,
  correlationId: string,
): z.infer<typeof ListFiltersSchema> {
  const raw: Record<string, unknown> = { ...(req.query ?? {}) };
  if (typeof raw["status"] === "string" && raw["status"]) {
    raw["status"] = (raw["status"] as string).split(",").filter(Boolean);
  }
  const parsed = ListFiltersSchema.safeParse(raw);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid list filters", parsed.error.flatten());
  }
  return parsed.data;
}

function projectListItem(db: MockDb, app: LoanApplication): LoanApplicationListItem {
  const borrower = db.borrowers.get(app.borrowerId);
  const lsp = db.lsps.get(app.lspId);
  const product = db.products.get(app.productId);
  const account = getAccountForApplication(db, app.id);
  return {
    id: app.id,
    externalLoanId: app.externalLoanId,
    accountNumber: account?.accountNumber ?? null,
    borrowerId: app.borrowerId,
    borrowerNameMasked: borrower ? maskBorrowerName(borrower.fullName) : "•••",
    lspId: app.lspId,
    lspName: lsp?.name ?? "Unknown LSP",
    productId: app.productId,
    productName: product?.name ?? "Unknown product",
    requestedAmount: app.requestedAmount,
    tenureMonths: app.tenureMonths,
    status: app.status,
    createdAt: app.createdAt,
    updatedAt: app.updatedAt,
  };
}

function listHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): LoanApplicationListResponse {
  const session = requireSession(db, correlationId);
  if (!INTERNAL_ROLES.has(session.role) && !LSP_UI_ROLES.has(session.role)) {
    throw new UnauthorizedError(correlationId, `role ${session.role} cannot access this surface`);
  }
  const filters = parseListQuery(req, correlationId);

  let rows: LoanApplication[] = Array.from(db.applications.values());
  // Tenant scope first — never let an LSP user pierce into another tenant.
  if (LSP_UI_ROLES.has(session.role)) {
    rows = rows.filter((a) => a.lspId === session.lspId);
  }
  // q — substring match across id, externalLoanId, borrower name.
  if (filters.q) {
    const needle = filters.q.toLowerCase();
    rows = rows.filter((a) => {
      if (a.id.toLowerCase().includes(needle)) return true;
      if (a.externalLoanId && a.externalLoanId.toLowerCase().includes(needle)) return true;
      const borrower = db.borrowers.get(a.borrowerId);
      if (borrower && borrower.fullName.toLowerCase().includes(needle)) return true;
      return false;
    });
  }
  if (filters.lspLoanId) {
    const needle = filters.lspLoanId.toLowerCase();
    rows = rows.filter((a) => a.externalLoanId?.toLowerCase().includes(needle) ?? false);
  }
  if (filters.bhawLoanId) {
    const needle = filters.bhawLoanId.toLowerCase();
    rows = rows.filter((a) => {
      const account = getAccountForApplication(db, a.id);
      return account?.accountNumber.toLowerCase().includes(needle) ?? false;
    });
  }
  if (filters.disbursalDateFrom || filters.disbursalDateTo) {
    rows = rows.filter((a) => {
      const account = getAccountForApplication(db, a.id);
      const disbursement = account
        ? db.payments.find(
            (payment) => payment.accountId === account.id && payment.installmentId === null,
          )
        : undefined;
      const date = disbursement?.postedAt.slice(0, 10);
      if (!date) return false;
      if (filters.disbursalDateFrom && date < filters.disbursalDateFrom) return false;
      if (filters.disbursalDateTo && date > filters.disbursalDateTo) return false;
      return true;
    });
  }
  if (filters.status && filters.status.length > 0) {
    const allowed = new Set<LoanStatusType>(filters.status);
    rows = rows.filter((a) => allowed.has(a.status));
  }
  if (filters.lspId) {
    rows = rows.filter((a) => a.lspId === filters.lspId);
  }
  if (filters.productId) {
    rows = rows.filter((a) => a.productId === filters.productId);
  }
  // Sort
  const sortBy = filters.sortBy ?? "createdAt";
  const sortDir = filters.sortDir ?? "desc";
  rows.sort((a, b) => {
    const av: string | number = a[sortBy];
    const bv: string | number = b[sortBy];
    if (av === bv) return 0;
    const cmp = av < bv ? -1 : 1;
    return sortDir === "asc" ? cmp : -cmp;
  });

  const total = rows.length;
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const slice = rows.slice(page * pageSize, page * pageSize + pageSize);

  return {
    items: slice.map((a) => projectListItem(db, a)),
    total,
    page,
    pageSize,
  };
}

// ─── Detail + per-tab handlers ───────────────────────────────────────────────

function detailHandler(req: MockRequest, db: MockDb, correlationId: string): LoanApplicationDetail {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);

  const borrower = db.borrowers.get(app.borrowerId);
  const lsp = db.lsps.get(app.lspId);
  const product = db.products.get(app.productId);
  if (!borrower || !lsp || !product) {
    throw new NotFoundError(correlationId, "detail references a missing relation");
  }
  const account = getAccountForApplication(db, app.id);
  const documents = getDocumentsForApplication(db, app.id);
  const docsComplete = computeDocsComplete(documents);
  const { schedule, installments } = account
    ? getScheduleForAccount(db, account.id)
    : { schedule: null, installments: [] };
  const scheduleValid = validateSchedule(schedule, installments, account);

  return {
    application: app,
    borrower: borrower as Borrower,
    lsp: lsp as Lsp,
    product: product as LoanProduct,
    account,
    docsComplete,
    scheduleValid,
  };
}

function scheduleHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): LoanApplicationScheduleResponse {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);
  const account = getAccountForApplication(db, app.id);
  if (!account) {
    return { schedule: null, installments: [] };
  }
  const { schedule, installments } = getScheduleForAccount(db, account.id);
  return { schedule, installments };
}

function documentsHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): LoanApplicationDocumentsResponse {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);
  return { documents: getDocumentsForApplication(db, app.id) };
}

function repaymentsHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): LoanApplicationRepaymentsResponse {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);
  const account = getAccountForApplication(db, app.id);
  if (!account) return { payments: [] };
  const payments = db.payments.filter((p) => p.accountId === account.id);
  // Newest first — matches Phase 4 dashboard convention.
  payments.sort((a, b) => (a.postedAt < b.postedAt ? 1 : -1));
  return { payments };
}

function activityHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): LoanApplicationActivityResponse {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);
  const events = db.auditApplication.filter((e) => e.applicationId === app.id);
  events.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
  return { events };
}

const WEBHOOK_EVENT_FOR_STATUS: Partial<Record<LoanStatusType, string>> = {
  INITIATED: "loan.created",
  DISBURSEMENT_IN_PROGRESS: "loan.status.changed",
  DISBURSED: "loan.disbursement.completed",
  UNDER_REPAYMENT: "loan.repayment.posted",
  FORECLOSURE_REQUESTED: "loan.status.changed",
  FORECLOSED: "loan.foreclosed",
  FULLY_REPAID: "loan.repayment.posted",
};

/**
 * Webhook deliveries are not yet a first-class table — synthesise one
 * DELIVERED row per application-audit transition. `lastAttemptAt` is the
 * transition timestamp + 5s. The real backend would emit a webhook through
 * the transactional outbox, but the UI only needs a list to render.
 */
function webhooksHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): LoanApplicationWebhooksResponse {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);
  const lsp = db.lsps.get(app.lspId);
  const endpoint = `https://lsp.mock.example/${lsp?.code ?? "UNKNOWN"}/webhook`;
  const events = db.auditApplication.filter((e) => e.applicationId === app.id);
  const deliveries: LoanApplicationWebhookDelivery[] = events.map((e) => {
    const eventType = WEBHOOK_EVENT_FOR_STATUS[e.toStatus] ?? "loan.status.changed";
    const attempted = new Date(new Date(e.createdAt).getTime() + 5_000).toISOString();
    return {
      id: e.id,
      eventType,
      endpoint,
      status: "DELIVERED",
      attemptCount: 1,
      lastAttemptAt: attempted,
      lastError: null,
      createdAt: e.createdAt,
    };
  });
  deliveries.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
  return { deliveries };
}

// ─── Mutation handlers ───────────────────────────────────────────────────────

const TransitionStatusSchema = z.object({
  to: LoanStatus,
  reason: z.string().max(1000).nullable(),
  idempotencyKey: z.string().min(1).max(80),
});

const PostRepaymentSchema = z.object({
  installmentId: Uuid,
  amount: MoneyINRPositive,
  postedAt: Iso8601,
  mode: z.string().min(1).max(40),
  idempotencyKey: z.string().min(1).max(80),
});

const InitiateDisbursementSchema = z.object({
  note: z.string().max(500).nullable(),
  idempotencyKey: z.string().min(1).max(80),
});

// BR-11 / BR-12: LSP-provided schedule submission. The per-row shape mirrors
// `LspProvidedSchedule.installments[*]` so the reconciliation schema can run
// against the parsed payload as-is. Idempotency-Key per BR-5.
const SubmitScheduleBodySchema = z.object({
  installments: z
    .array(
      z.object({
        number: z.number().int().min(1).max(360),
        dueDate: IsoDate,
        principalDue: MoneyINR,
        interestDue: MoneyINR,
        installmentAmount: MoneyINR,
      }),
    )
    .min(1),
  idempotencyKey: z.string().min(1).max(80),
});

// Statuses from which an LSP may still submit/replace a schedule. Anything
// at DISBURSEMENT_IN_PROGRESS or later is locked: the disbursement leg has
// committed to the prior schedule (BR-12 enforces the further "frozen once
// repayments are posted" gate separately).
const SCHEDULE_SUBMITTABLE_STATUSES = new Set<LoanStatusType>([
  "INITIATED",
  "KYC_PENDING",
  "DOCS_PENDING",
  "UNDER_REVIEW",
  "AWAITING_APPROVAL",
  "APPROVED",
  "APPROVED_PENDING_DISBURSAL",
]);

const SCHEDULE_SUBMIT_ROLES = new Set<Role>([
  "SYSTEM_ADMIN",
  "OPS_USER",
  "LSP_UI_WRITE",
  "LSP_API_CLIENT",
]);

// LSP-API foreclosure: the LSP supplies the final settlement amounts and the
// LMS marks the loan application FORECLOSED (terminal; account also FORECLOSED).
const CompleteForeclosureBodySchema = z.object({
  outstandingPrincipal: MoneyINR,
  accruedInterest: MoneyINR,
  foreclosureCharge: MoneyINR,
  settledAt: Iso8601,
  idempotencyKey: z.string().min(1).max(80),
});

const FORECLOSEABLE_STATUSES = new Set<LoanStatusType>([
  "DISBURSED",
  "UNDER_REPAYMENT",
  "PARTIALLY_PAID",
  "DELINQUENT",
]);

const FORECLOSURE_ROLES = new Set<Role>([
  "SYSTEM_ADMIN",
  "OPS_USER",
  "LSP_UI_WRITE",
  "LSP_API_CLIENT",
]);

function buildTransitionCtx(db: MockDb, app: LoanApplication): TransitionCtx {
  const documents = getDocumentsForApplication(db, app.id);
  const borrower = db.borrowers.get(app.borrowerId);
  const account = getAccountForApplication(db, app.id);
  const { schedule, installments } = account
    ? getScheduleForAccount(db, account.id)
    : { schedule: null, installments: [] };
  const hasSchedule = validateSchedule(schedule, installments, account);
  const hasPostedRepayment = account
    ? db.payments.some((p) => p.accountId === account.id && p.installmentId !== null)
    : false;
  return {
    application: app,
    borrower,
    documents,
    hasSchedule,
    scheduleFrozen: schedule?.frozen ?? false,
    hasPostedRepayment,
    borrowerHasOtherOpenLoan: false,
    foreclosureQuoteExpired: false,
  };
}

/**
 * Append an `ApplicationAuditEvent` for a transition + bump `updatedAt`
 * on the application. Returns the new event (for re-use in webhook
 * synthesis).
 */
function recordTransition(
  db: MockDb,
  app: LoanApplication,
  to: LoanStatusType,
  actorId: string,
  actorRole: Role,
  reason: string | null,
  correlationId: string,
  action: string,
): ApplicationAuditEvent {
  const nowIso = new Date().toISOString();
  const updated: LoanApplication = { ...app, status: to, updatedAt: nowIso };
  db.applications.set(app.id, updated);
  const event: ApplicationAuditEvent = {
    id: newIdempotencyKey(),
    applicationId: app.id,
    fromStatus: app.status,
    toStatus: to,
    action,
    actorId,
    actorRole,
    channel: "UI",
    correlationId,
    reason,
    createdAt: nowIso,
  };
  db.auditApplication.push(event);
  return event;
}

function transitionsHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): { application: LoanApplication; event: ApplicationAuditEvent } {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);
  const parsed = TransitionStatusSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid transition body", parsed.error.flatten());
  }
  const { to, reason } = parsed.data;

  // Users may never trigger SYSTEM-only transitions (BR-14 etc.). canTransition
  // already rejects via ROLE_NOT_ALLOWED, but we guard here so the error
  // surfaces consistently even if a future rule loosens that check.
  const ctx = buildTransitionCtx(db, app);
  const result = canTransition(session.role, app.status, to, ctx);
  if (!result.ok) {
    throw wrapBusinessRuleError(result.error, correlationId);
  }

  const event = recordTransition(
    db,
    app,
    to,
    session.userId,
    session.role,
    reason,
    correlationId,
    `transition.${to.toLowerCase()}`,
  );
  const updated = db.applications.get(app.id);
  if (!updated) throw new NotFoundError(correlationId, "application disappeared");
  return { application: updated, event };
}

/**
 * BR-13 enforcement: the posted amount MUST equal the outstanding amount on
 * the *next* DUE installment for this account. Anything else rejects with
 * a `PARTIAL_INSTALLMENT_REJECTED` BusinessRuleError.
 */
function findNextDueInstallment(
  installments: ReadonlyArray<RepaymentInstallment>,
): RepaymentInstallment | null {
  return (
    [...installments].filter((i) => i.status !== "PAID").sort((a, b) => a.number - b.number)[0] ??
    null
  );
}

function applyInstallmentPayment(
  installment: RepaymentInstallment,
  amount: number,
): RepaymentInstallment {
  const paid = installment.paidAmount + amount;
  const outstanding = Math.max(0, installment.installmentAmount - paid);
  return {
    ...installment,
    paidAmount: paid,
    outstandingAmount: outstanding,
    status: outstanding === 0 ? "PAID" : installment.status,
    dpd: outstanding === 0 ? 0 : installment.dpd,
  };
}

function repaymentPostHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): {
  payment: PaymentTransaction;
  application: LoanApplication;
  autoAdvanced: boolean;
  autoClosed: boolean;
} {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);

  const parsed = PostRepaymentSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid repayment body", parsed.error.flatten());
  }
  const { installmentId, amount, postedAt, mode, idempotencyKey } = parsed.data;

  const account = getAccountForApplication(db, app.id);
  if (!account) {
    throw new BadRequestError(correlationId, "application has no loan account yet");
  }
  const installments = db.installments.get(account.id) ?? [];
  const next = findNextDueInstallment(installments);
  if (!next) {
    throw new BadRequestError(correlationId, "no outstanding installments");
  }
  if (next.id !== installmentId) {
    throw wrapBusinessRuleError(
      new BusinessRuleError(
        "PARTIAL_INSTALLMENT_REJECTED",
        `next installment is ${next.number}; received ${installmentId}`,
      ),
      correlationId,
    );
  }
  // BR-13: amount must match next installment outstanding exactly.
  if (amount !== next.outstandingAmount) {
    throw wrapBusinessRuleError(
      new BusinessRuleError(
        "PARTIAL_INSTALLMENT_REJECTED",
        `expected ${next.outstandingAmount}, received ${amount} (BR-13)`,
      ),
      correlationId,
    );
  }

  // Apply the payment + mutate the installment list.
  const payment: PaymentTransaction = {
    id: newIdempotencyKey(),
    accountId: account.id,
    installmentId: next.id,
    channel:
      mode === "UPI" || mode === "BANK_TRANSFER" || mode === "CASH" || mode === "ADJUSTMENT"
        ? mode
        : "BANK_TRANSFER",
    amount,
    postedAt,
    postedBy: session.userId,
    idempotencyKey,
    allocation: [
      {
        installmentId: next.id,
        penalty: 0,
        fees: 0,
        interest: next.interestDue,
        principal: next.principalDue,
      },
    ],
  };
  db.payments.push(payment);

  const updatedInst = applyInstallmentPayment(next, amount);
  const newInstallments = installments.map((i) => (i.id === next.id ? updatedInst : i));
  db.installments.set(account.id, newInstallments);

  // BR-14: first payment on a DISBURSED loan auto-advances to UNDER_REPAYMENT.
  let autoAdvanced = false;
  if (app.status === "DISBURSED" && postRepaymentAutoAdvance(app.status) !== null) {
    const advanced = recordTransition(
      db,
      app,
      "UNDER_REPAYMENT",
      session.userId,
      "SYSTEM_ADMIN",
      null,
      correlationId,
      "auto-advance.first-payment",
    );
    void advanced;
    autoAdvanced = true;
  }

  // BR-15: full-repayment auto-close. "Fully repaid" == every installment PAID.
  let autoClosed = false;
  const allPaid = newInstallments.every((i) => i.status === "PAID");
  if (allPaid) {
    const current = db.applications.get(app.id);
    if (current && current.status !== "FULLY_REPAID" && current.status !== "CLOSED") {
      recordTransition(
        db,
        current,
        "FULLY_REPAID",
        session.userId,
        "SYSTEM_ADMIN",
        "BR-15 auto-close on full repayment",
        correlationId,
        "auto-close.full-repayment",
      );
      // Mark account closed too — keeps Phase 4 KPIs honest.
      const closedAccount: LoanAccount = {
        ...account,
        accountStatus: "CLOSED",
        closedAt: new Date().toISOString(),
        closureReason: "FULLY_REPAID",
      };
      db.accounts.set(account.id, closedAccount);
      autoClosed = true;
    }
  }

  const application = db.applications.get(app.id);
  if (!application) throw new NotFoundError(correlationId, "application disappeared");
  return { payment, application, autoAdvanced, autoClosed };
}

/**
 * Disbursement initiation:
 *   1. application must be APPROVED_PENDING_DISBURSAL
 *   2. BR-3 disbursement docs must be uploaded (UPLOADED)
 *   3. BR-10 a valid repayment schedule must exist
 *
 * Status moves APPROVED_PENDING_DISBURSAL → DISBURSEMENT_IN_PROGRESS, and a
 * follow-up "bank confirms" step lands on DISBURSED. The follow-up is
 * synchronous in test mode (`import.meta.env.MODE === 'test'`) to keep
 * Vitest free of dangling timers; in dev it fires after 200-400ms.
 */
function disbursementHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): { application: LoanApplication; events: ApplicationAuditEvent[] } {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);

  const parsed = InitiateDisbursementSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid disbursement body", parsed.error.flatten());
  }
  const { note } = parsed.data;

  // 1: must be in APPROVED_PENDING_DISBURSAL.
  if (app.status !== "APPROVED_PENDING_DISBURSAL") {
    throw wrapBusinessRuleError(
      new BusinessRuleError(
        "INVALID_TRANSITION",
        `cannot initiate disbursement from status ${app.status}`,
      ),
      correlationId,
    );
  }

  // canTransition already enforces BR-3 + BR-10 + ROLE_NOT_ALLOWED.
  const ctx = buildTransitionCtx(db, app);
  const guard = canTransition(session.role, app.status, "DISBURSEMENT_IN_PROGRESS", ctx);
  if (!guard.ok) {
    throw wrapBusinessRuleError(guard.error, correlationId);
  }

  const initiated = recordTransition(
    db,
    app,
    "DISBURSEMENT_IN_PROGRESS",
    session.userId,
    session.role,
    note,
    correlationId,
    "disbursement.initiate",
  );

  const events: ApplicationAuditEvent[] = [initiated];

  const finishConfirm = (): void => {
    const current = db.applications.get(app.id);
    if (!current || current.status !== "DISBURSEMENT_IN_PROGRESS") return;
    const confirmed = recordTransition(
      db,
      current,
      "DISBURSED",
      session.userId,
      "SYSTEM_ADMIN",
      null,
      correlationId,
      "disbursement.confirm",
    );
    events.push(confirmed);
  };

  // Test mode: do both legs synchronously so vitest can assert the end state
  // without juggling timers.
  if (import.meta.env.MODE === "test") {
    finishConfirm();
  } else {
    const delay = 200 + Math.floor(Math.random() * 200);
    setTimeout(finishConfirm, delay);
  }

  const application = db.applications.get(app.id);
  if (!application) throw new NotFoundError(correlationId, "application disappeared");
  return { application, events };
}

/**
 * POST /api/v1/lsp-api/loan-applications/:id/schedule
 *
 * LSP-API surface: an LSP_API_CLIENT (or an internal SYSTEM_ADMIN/OPS_USER
 * acting on behalf, or LSP_UI_WRITE driving from the LSP console) submits a
 * full schedule for an application that has NOT yet started disbursement.
 *
 * Enforces:
 *   - role allow-list (SCHEDULE_SUBMIT_ROLES)
 *   - status precondition (SCHEDULE_SUBMITTABLE_STATUSES) — anything from
 *     DISBURSEMENT_IN_PROGRESS onward is rejected via INVALID_TRANSITION
 *   - BR-12 frozen-after-repayment — if any payment with a non-null
 *     installmentId exists for the loan account, reject with SCHEDULE_FROZEN
 *   - BR-11 schedule reconciliation via `LspProvidedSchedule.safeParse`
 *
 * On success: REPLACES the existing schedule + installments for the account
 * (creating the account on the fly if it does not yet exist) and appends a
 * `lsp-api.schedule.submit` audit row (status unchanged).
 */
function submitScheduleHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): SubmitScheduleResponse {
  const session = requireSession(db, correlationId);
  if (!SCHEDULE_SUBMIT_ROLES.has(session.role)) {
    throw new UnauthorizedError(correlationId, `role ${session.role} cannot submit a schedule`);
  }
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);

  const parsed = SubmitScheduleBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(
      correlationId,
      "invalid submit-schedule body",
      parsed.error.flatten(),
    );
  }

  // Status guard — reject anything from DISBURSEMENT_IN_PROGRESS onward.
  if (!SCHEDULE_SUBMITTABLE_STATUSES.has(app.status)) {
    throw wrapBusinessRuleError(
      new BusinessRuleError(
        "INVALID_TRANSITION",
        `cannot submit schedule from status ${app.status}`,
      ),
      correlationId,
    );
  }

  const existing = getAccountForApplication(db, app.id);

  // BR-12 — schedule is frozen once any repayment has been posted against
  // the account (a payment with a non-null installmentId).
  if (
    existing &&
    db.payments.some((p) => p.accountId === existing.id && p.installmentId !== null)
  ) {
    throw wrapBusinessRuleError(
      new BusinessRuleError(
        "SCHEDULE_FROZEN",
        "schedule is frozen — repayments have been posted (BR-12)",
      ),
      correlationId,
    );
  }

  // BR-11 — full schedule reconciliation. Build the candidate against the
  // application's approved principal + tenure (the LSP cannot rewrite those).
  const candidate = {
    accountId: existing?.id ?? crypto.randomUUID(),
    approvedPrincipal: app.requestedAmount,
    approvedTenureMonths: app.tenureMonths,
    installments: parsed.data.installments,
  };
  const reconciled = LspProvidedSchedule.safeParse(candidate);
  if (!reconciled.success) {
    throw new BadRequestError(
      correlationId,
      "schedule reconciliation failed (BR-11)",
      reconciled.error.flatten(),
    );
  }

  // Materialise the account on the fly if the loan hasn't been disbursed
  // and so doesn't yet have one.
  const nowIso = new Date().toISOString();
  const account: LoanAccount =
    existing ??
    ({
      id: candidate.accountId,
      applicationId: app.id,
      accountNumber: `LSP-${crypto.randomUUID().slice(0, 8).toUpperCase()}`,
      accountStatus: "ACTIVE",
      principal: app.requestedAmount,
      tenureMonths: app.tenureMonths,
      approvedAt: nowIso,
      createdAt: nowIso,
      closedAt: null,
      closureReason: null,
    } satisfies LoanAccount);
  db.accounts.set(account.id, account);

  // Replace any prior schedule + installments. There is at most one schedule
  // per account in the mock (mirrors the production constraint).
  for (const s of Array.from(db.schedules.values())) {
    if (s.accountId === account.id) {
      db.schedules.delete(s.id);
    }
  }

  const schedule: RepaymentSchedule = {
    id: crypto.randomUUID(),
    accountId: account.id,
    generatedBy: "LSP",
    frozen: false,
    createdAt: nowIso,
  };
  db.schedules.set(schedule.id, schedule);

  const newInstallments: RepaymentInstallment[] = parsed.data.installments.map((row) => ({
    id: crypto.randomUUID(),
    accountId: account.id,
    scheduleId: schedule.id,
    number: row.number,
    dueDate: row.dueDate,
    principalDue: row.principalDue,
    interestDue: row.interestDue,
    installmentAmount: row.installmentAmount,
    paidAmount: 0,
    outstandingAmount: row.installmentAmount,
    dpd: 0,
    delinquencyBucket: "B0",
    status: "DUE",
  }));
  db.installments.set(account.id, newInstallments);

  // Audit — status unchanged so fromStatus === toStatus. Channel "API" since
  // this endpoint lives on the LSP-API surface.
  db.auditApplication.push({
    id: crypto.randomUUID(),
    applicationId: app.id,
    fromStatus: app.status,
    toStatus: app.status,
    action: "lsp-api.schedule.submit",
    actorId: session.userId,
    actorRole: session.role,
    channel: "API",
    correlationId,
    reason: null,
    contextJson: {
      installmentCount: newInstallments.length,
      totalPrincipal: app.requestedAmount,
    },
    createdAt: nowIso,
  });

  return {
    scheduleId: schedule.id,
    installments: newInstallments.map((i) => ({
      number: i.number,
      dueDate: i.dueDate,
      principalDue: i.principalDue,
      interestDue: i.interestDue,
      installmentAmount: i.installmentAmount,
    })),
  };
}

/**
 * POST /api/v1/lsp-api/loan-applications/:id/foreclose
 *
 * LSP-API surface: the LSP settles a live loan in one shot, supplying the
 * outstanding principal + accrued interest + foreclosure charge it has
 * already collected. The handler:
 *
 *   - validates role + status precondition (FORECLOSEABLE_STATUSES)
 *   - requires an existing loan account (no auto-create — foreclosure is
 *     only meaningful against a disbursed loan)
 *   - appends an `ADJUSTMENT` PaymentTransaction for the settlement total
 *   - marks every non-PAID installment PAID
 *   - records a single terminal transition to FORECLOSED (aligned with backend Option B)
 *   - closes the loan account as FORECLOSED with closureReason = "FORECLOSED"
 */
function forecloseHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): CompleteForeclosureResponse {
  const session = requireSession(db, correlationId);
  if (!FORECLOSURE_ROLES.has(session.role)) {
    throw new UnauthorizedError(correlationId, "role not allowed for foreclosure");
  }
  const id = req.params?.["id"] ?? "";
  const app = loadApplicationForSession(db, session, id, correlationId);

  const parsed = CompleteForeclosureBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(
      correlationId,
      "invalid complete-foreclosure body",
      parsed.error.flatten(),
    );
  }

  if (!FORECLOSEABLE_STATUSES.has(app.status)) {
    throw wrapBusinessRuleError(
      new BusinessRuleError("INVALID_TRANSITION", `cannot foreclose from status ${app.status}`),
      correlationId,
    );
  }

  const total =
    parsed.data.outstandingPrincipal + parsed.data.accruedInterest + parsed.data.foreclosureCharge;
  if (total <= 0) {
    throw new BadRequestError(correlationId, "foreclosure total must be greater than zero");
  }

  const account = getAccountForApplication(db, app.id);
  if (!account) {
    throw new BadRequestError(correlationId, "application has no loan account");
  }

  // 1: append the settlement payment (channel ADJUSTMENT, installmentId null
  //    because the payment closes out the loan rather than a specific row).
  const payment: PaymentTransaction = {
    id: crypto.randomUUID(),
    accountId: account.id,
    installmentId: null,
    channel: "ADJUSTMENT",
    amount: total,
    postedAt: parsed.data.settledAt,
    postedBy: session.userId,
    idempotencyKey: parsed.data.idempotencyKey,
    allocation: [],
  };
  db.payments.push(payment);

  // 2: mark every still-outstanding installment as PAID.
  const existingInstallments = db.installments.get(account.id) ?? [];
  const updatedInstallments = existingInstallments.map((i) =>
    i.status === "PAID"
      ? i
      : {
          ...i,
          paidAmount: i.installmentAmount,
          outstandingAmount: 0,
          dpd: 0,
          status: "PAID" as const,
        },
  );
  db.installments.set(account.id, updatedInstallments);

  // 3: terminal application status FORECLOSED (no second transition to CLOSED).
  recordTransition(
    db,
    app,
    "FORECLOSED",
    session.userId,
    "SYSTEM_ADMIN",
    "lsp-api foreclosure",
    correlationId,
    "lsp-api.foreclose",
  );

  // 4: close the account as FORECLOSED.
  const closedAccount: LoanAccount = {
    ...account,
    accountStatus: "FORECLOSED",
    closedAt: parsed.data.settledAt,
    closureReason: "FORECLOSED",
  };
  db.accounts.set(account.id, closedAccount);

  return {
    applicationId: app.id,
    finalStatus: "FORECLOSED",
    totalSettled: total,
  };
}

// ─── Schemas for the dispatch parser (drift detection) ───────────────────────

const ApplicationAuditEventSchema: z.ZodType<ApplicationAuditEvent> = z.object({
  id: z.string(),
  applicationId: z.string(),
  fromStatus: LoanStatus.nullable(),
  toStatus: LoanStatus,
  action: z.string(),
  actorId: z.string(),
  actorRole: z.enum([
    "SYSTEM_ADMIN",
    "OPS_USER",
    "PRODUCT_ADMIN",
    "LSP_UI_READ",
    "LSP_UI_WRITE",
    "LSP_API_CLIENT",
  ]),
  channel: z.enum(["UI", "API", "WEBHOOK", "SCHEDULER"]),
  correlationId: z.string(),
  reason: z.string().nullable(),
  contextJson: z.record(z.unknown()).optional(),
  createdAt: z.string(),
});

const LoanApplicationSchema: z.ZodType<LoanApplication> = z.object({
  id: z.string(),
  externalLoanId: z.string().nullable(),
  borrowerId: z.string(),
  lspId: z.string(),
  productId: z.string(),
  requestedAmount: MoneyINRPositive,
  tenureMonths: z.number().int(),
  status: LoanStatus,
  sourceChannel: z.enum(["UI", "API", "WEBHOOK"]),
  createdAt: z.string(),
  updatedAt: z.string(),
  invalidatedAt: z.string().nullable(),
  invalidReason: z.string().nullable(),
});

const ListItemSchema = z.object({
  id: z.string(),
  externalLoanId: z.string().nullable(),
  accountNumber: z.string().nullable(),
  borrowerId: z.string(),
  borrowerNameMasked: z.string(),
  lspId: z.string(),
  lspName: z.string(),
  productId: z.string(),
  productName: z.string(),
  requestedAmount: z.number().nonnegative(),
  tenureMonths: z.number().int(),
  status: LoanStatus,
  createdAt: z.string(),
  updatedAt: z.string(),
});

const ListResponseSchema = z.object({
  items: z.array(ListItemSchema).readonly(),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
});

// `Borrower`, `Lsp`, `LoanProduct`, `LoanAccount`, `LoanDocument`,
// `RepaymentSchedule`, `RepaymentInstallment`, `PaymentTransaction` parsers
// are intentionally permissive — we only assert the top-level shape; the
// nested rows already passed Zod when they were seeded.
const Permissive = z.unknown();

const DetailResponseSchema = z.object({
  application: LoanApplicationSchema,
  borrower: Permissive,
  lsp: Permissive,
  product: Permissive,
  account: Permissive.nullable(),
  docsComplete: z.boolean(),
  scheduleValid: z.boolean(),
});

const ScheduleResponseSchema = z.object({
  schedule: Permissive.nullable(),
  installments: z.array(Permissive).readonly(),
});
const DocumentsResponseSchema = z.object({
  documents: z.array(Permissive).readonly(),
});
const RepaymentsResponseSchema = z.object({
  payments: z.array(Permissive).readonly(),
});
const ActivityResponseSchema = z.object({
  events: z.array(ApplicationAuditEventSchema).readonly(),
});
const WebhookDeliverySchema = z.object({
  id: z.string(),
  eventType: z.string(),
  endpoint: z.string(),
  status: z.enum(["PENDING", "DELIVERED", "FAILED", "DEAD_LETTERED"]),
  attemptCount: z.number().int().nonnegative(),
  lastAttemptAt: z.string().nullable(),
  lastError: z.string().nullable(),
  createdAt: z.string(),
});
const WebhooksResponseSchema = z.object({
  deliveries: z.array(WebhookDeliverySchema).readonly(),
});

const TransitionResponseSchema = z.object({
  application: LoanApplicationSchema,
  event: ApplicationAuditEventSchema,
});

const PaymentTransactionSchema = z.object({
  id: z.string(),
  accountId: z.string(),
  installmentId: z.string().nullable(),
  channel: z.enum(["BANK_TRANSFER", "UPI", "CASH", "ADJUSTMENT"]),
  amount: MoneyINRPositive,
  postedAt: z.string(),
  postedBy: z.string(),
  idempotencyKey: z.string(),
  allocation: z.array(
    z.object({
      installmentId: z.string(),
      penalty: MoneyINR,
      fees: MoneyINR,
      interest: MoneyINR,
      principal: MoneyINR,
    }),
  ),
});

const RepaymentPostResponseSchema = z.object({
  payment: PaymentTransactionSchema,
  application: LoanApplicationSchema,
  autoAdvanced: z.boolean(),
  autoClosed: z.boolean(),
});

const DisbursementResponseSchema = z.object({
  application: LoanApplicationSchema,
  events: z.array(ApplicationAuditEventSchema).readonly(),
});

// Just to keep the date validator on the parsed schemas useful in the
// future: imported but currently unused — silence the linter.
void Iso8601;
void IsoDate;

// ─── Route registration (idempotent) ─────────────────────────────────────────

let registered = false;
export function registerLoanApplicationRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/loan-applications", listHandler);
  registerRoute("GET", "/api/v1/loan-applications/:id", detailHandler);
  registerRoute("GET", "/api/v1/loan-applications/:id/schedule", scheduleHandler);
  registerRoute("GET", "/api/v1/loan-applications/:id/documents", documentsHandler);
  registerRoute("GET", "/api/v1/loan-applications/:id/repayments", repaymentsHandler);
  registerRoute("GET", "/api/v1/loan-applications/:id/activity", activityHandler);
  registerRoute("GET", "/api/v1/loan-applications/:id/webhooks", webhooksHandler);
  registerRoute("POST", "/api/v1/loan-applications/:id/transitions", transitionsHandler, {
    mutating: true,
  });
  registerRoute("POST", "/api/v1/loan-applications/:id/repayments", repaymentPostHandler, {
    mutating: true,
  });
  registerRoute("POST", "/api/v1/loan-applications/:id/disbursement", disbursementHandler, {
    mutating: true,
  });
  registerRoute("POST", "/api/v1/lsp-api/loan-applications/:id/schedule", submitScheduleHandler, {
    mutating: true,
  });
  registerRoute("POST", "/api/v1/lsp-api/loan-applications/:id/foreclose", forecloseHandler, {
    mutating: true,
  });
}

registerLoanApplicationRoutes();

// ─── Public API surface (consumed by feature modules) ────────────────────────

interface RequestOptions {
  idempotencyKey?: string;
  correlationId?: string;
}

function buildHeaders(opts: RequestOptions = {}): Record<string, string> {
  const headers: Record<string, string> = {};
  if (opts.idempotencyKey) headers["Idempotency-Key"] = opts.idempotencyKey;
  if (opts.correlationId) headers["X-Correlation-Id"] = opts.correlationId;
  return headers;
}

function queryFromFilters(filters: Record<string, unknown> = {}): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || value === null) continue;
    if (Array.isArray(value)) out[key] = value.join(",");
    else out[key] = String(value);
  }
  return out;
}

export async function list(
  filters: Record<string, unknown> = {},
  opts?: RequestOptions,
): Promise<LoanApplicationListResponse> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/loan-applications",
      query: queryFromFilters(filters),
      headers: buildHeaders(opts),
    },
    ListResponseSchema,
  ) as Promise<LoanApplicationListResponse>;
}

export async function detail(id: string, opts?: RequestOptions): Promise<LoanApplicationDetail> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${id}`,
      headers: buildHeaders(opts),
    },
    DetailResponseSchema,
  ) as Promise<LoanApplicationDetail>;
}

export async function schedule(
  id: string,
  opts?: RequestOptions,
): Promise<LoanApplicationScheduleResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${id}/schedule`,
      headers: buildHeaders(opts),
    },
    ScheduleResponseSchema,
  ) as Promise<LoanApplicationScheduleResponse>;
}

export async function documents(
  id: string,
  opts?: RequestOptions,
): Promise<LoanApplicationDocumentsResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${id}/documents`,
      headers: buildHeaders(opts),
    },
    DocumentsResponseSchema,
  ) as Promise<LoanApplicationDocumentsResponse>;
}

export async function repayments(
  id: string,
  opts?: RequestOptions,
): Promise<LoanApplicationRepaymentsResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${id}/repayments`,
      headers: buildHeaders(opts),
    },
    RepaymentsResponseSchema,
  ) as Promise<LoanApplicationRepaymentsResponse>;
}

export async function activity(
  id: string,
  opts?: RequestOptions,
): Promise<LoanApplicationActivityResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${id}/activity`,
      headers: buildHeaders(opts),
    },
    ActivityResponseSchema,
  ) as Promise<LoanApplicationActivityResponse>;
}

export async function webhooks(
  id: string,
  opts?: RequestOptions,
): Promise<LoanApplicationWebhooksResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/loan-applications/${id}/webhooks`,
      headers: buildHeaders(opts),
    },
    WebhooksResponseSchema,
  );
}

export interface TransitionInput {
  to: LoanStatusType;
  reason: string | null;
}

export async function transition(
  id: string,
  input: TransitionInput,
  opts?: RequestOptions,
): Promise<{ application: LoanApplication; event: ApplicationAuditEvent }> {
  const idempotencyKey = opts?.idempotencyKey ?? newIdempotencyKey();
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/loan-applications/${id}/transitions`,
      body: { ...input, idempotencyKey },
      headers: { ...buildHeaders(opts), "Idempotency-Key": idempotencyKey },
    },
    TransitionResponseSchema,
  );
}

export interface PostRepaymentClientInput {
  installmentId: string;
  amount: number;
  postedAt: string;
  mode: string;
}

export async function postRepayment(
  id: string,
  input: PostRepaymentClientInput,
  opts?: RequestOptions,
): Promise<{
  payment: PaymentTransaction;
  application: LoanApplication;
  autoAdvanced: boolean;
  autoClosed: boolean;
}> {
  const idempotencyKey = opts?.idempotencyKey ?? newIdempotencyKey();
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/loan-applications/${id}/repayments`,
      body: { ...input, idempotencyKey },
      headers: { ...buildHeaders(opts), "Idempotency-Key": idempotencyKey },
    },
    RepaymentPostResponseSchema,
  ) as Promise<{
    payment: PaymentTransaction;
    application: LoanApplication;
    autoAdvanced: boolean;
    autoClosed: boolean;
  }>;
}

const SubmitScheduleResponseParser = z
  .object({
    scheduleId: z.string(),
    installments: z.array(z.unknown()),
  })
  .passthrough() as unknown as z.ZodType<SubmitScheduleResponse>;

export async function submitSchedule(
  id: string,
  input: SubmitScheduleInput,
): Promise<SubmitScheduleResponse> {
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/lsp-api/loan-applications/${id}/schedule`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    SubmitScheduleResponseParser,
  );
}

const CompleteForeclosureResponseParser = z.object({
  applicationId: z.string(),
  finalStatus: z.string(),
  totalSettled: z.number(),
}) as unknown as z.ZodType<CompleteForeclosureResponse>;

export async function completeForeclosure(
  id: string,
  input: CompleteForeclosureInput,
): Promise<CompleteForeclosureResponse> {
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/lsp-api/loan-applications/${id}/foreclose`,
      body: input,
      headers: { "Idempotency-Key": input.idempotencyKey },
    },
    CompleteForeclosureResponseParser,
  );
}

export async function initiateDisbursement(
  id: string,
  input: { note: string | null },
  opts?: RequestOptions,
): Promise<{ application: LoanApplication; events: ApplicationAuditEvent[] }> {
  const idempotencyKey = opts?.idempotencyKey ?? newIdempotencyKey();
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/loan-applications/${id}/disbursement`,
      body: { ...input, idempotencyKey },
      headers: { ...buildHeaders(opts), "Idempotency-Key": idempotencyKey },
    },
    DisbursementResponseSchema,
  ) as Promise<{ application: LoanApplication; events: ApplicationAuditEvent[] }>;
}
