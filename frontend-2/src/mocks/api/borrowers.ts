/**
 * Borrowers handler.
 *
 * Implements every endpoint the borrower-detail surface + the documents
 * tab consume.
 *
 * Endpoints:
 *   GET  /api/v1/borrowers/:id                       → BorrowerDetail
 *   GET  /api/v1/borrowers/:id/loans                 → BorrowerLoansResponse
 *   POST /api/v1/documents/:documentId/access        → RecordDocumentAccessResponse
 *
 * LSP scoping mirrors `loan-applications.ts`:
 *   SYSTEM_ADMIN / OPS_USER / PRODUCT_ADMIN  → see every borrower
 *   LSP_UI_READ  / LSP_UI_WRITE              → only borrowers whose
 *                                              `visibleLspIds` includes
 *                                              `session.user.lspId`
 *   anything else                            → 401
 *
 * Cross-tenant access raises `ForbiddenError` (HTTP 403).
 *
 * Per the masking-everywhere posture (see `docs/gap-fixes.md` § Gap #1),
 * the PII reveal endpoint is removed. Per § Gap #2, the borrower
 * activity feed is removed.
 */
import { z } from "zod";
import { Uuid } from "@/schemas/common";
import { DocumentAccessAction } from "@/schemas/audit";
import { hasPermission } from "@/lib/permissions";
import { newIdempotencyKey } from "@/lib/idempotency";
import { STATUS_META } from "@/lib/lifecycle";
import type {
  DocumentAccessEvent,
  LoanAccount,
  LoanApplication,
  LoanDocument,
  RepaymentInstallment,
  Role,
} from "@/types";
import type { Borrower } from "@/schemas/borrower";
import type {
  BorrowerDetail,
  BorrowerLoanRow,
  BorrowerLoansResponse,
  RecordDocumentAccessInput,
  RecordDocumentAccessResponse,
} from "@/features/borrowers/types";
import { dispatch, registerRoute, type MockRequest } from "../router";
import {
  BadRequestError,
  ForbiddenError,
  NotFoundError,
  UnauthorizedError,
  newCorrelationId,
} from "../errors";
import type { MockDb } from "../db/state";

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

/** True when the session can see this borrower (BR-7 tenant scope). */
function canSeeBorrower(session: ActiveSession, borrower: Borrower): boolean {
  if (INTERNAL_ROLES.has(session.role)) return true;
  if (LSP_UI_ROLES.has(session.role)) {
    return session.lspId !== null && borrower.visibleLspIds.includes(session.lspId);
  }
  return false;
}

function loadBorrowerForSession(
  db: MockDb,
  session: ActiveSession,
  id: string,
  correlationId: string,
): Borrower {
  const borrower = db.borrowers.get(id);
  if (!borrower) {
    throw new NotFoundError(correlationId, `borrower ${id} not found`);
  }
  if (!canSeeBorrower(session, borrower)) {
    throw new ForbiddenError(correlationId, "borrower out of tenant scope");
  }
  return borrower;
}

// ─── Aggregation helpers ─────────────────────────────────────────────────────

function getApplicationsForBorrower(db: MockDb, borrowerId: string): LoanApplication[] {
  const out: LoanApplication[] = [];
  for (const a of db.applications.values()) {
    if (a.borrowerId === borrowerId) out.push(a);
  }
  return out;
}

function getAccountForApplication(db: MockDb, applicationId: string): LoanAccount | null {
  for (const a of db.accounts.values()) {
    if (a.applicationId === applicationId) return a;
  }
  return null;
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

/** Sum overdue installment outstanding amounts across this borrower's accounts. */
function computeActiveOverdue(
  db: MockDb,
  accountIds: ReadonlyArray<string>,
): number {
  if (accountIds.length === 0) return 0;
  const today = todayIsoDate();
  let total = 0;
  for (const acctId of accountIds) {
    const installments: ReadonlyArray<RepaymentInstallment> =
      db.installments.get(acctId) ?? [];
    for (const inst of installments) {
      const isOverdue = inst.status === "OVERDUE" || (inst.dueDate < today && inst.status !== "PAID");
      if (isOverdue) {
        total += inst.outstandingAmount > 0 ? inst.outstandingAmount : inst.installmentAmount;
      }
    }
  }
  return total;
}

function projectDetail(db: MockDb, borrower: Borrower): BorrowerDetail {
  const apps = getApplicationsForBorrower(db, borrower.id);
  let openCount = 0;
  let closedCount = 0;
  const accountIds: string[] = [];
  let lifetimeDisbursed = 0;
  for (const app of apps) {
    if (STATUS_META[app.status].open) openCount++;
    else closedCount++;
    const acct = getAccountForApplication(db, app.id);
    if (acct) {
      accountIds.push(acct.id);
      lifetimeDisbursed += acct.principal;
    }
  }
  const activeOverdueAmount = computeActiveOverdue(db, accountIds);

  const visibleLsps = borrower.visibleLspIds.map((lspId) => {
    const lsp = db.lsps.get(lspId);
    return { id: lspId, name: lsp?.name ?? "Unknown LSP" };
  });

  return {
    borrower,
    visibleLsps,
    totals: {
      openApplicationsCount: openCount,
      closedApplicationsCount: closedCount,
      lifetimeDisbursedAmount: lifetimeDisbursed,
      activeOverdueAmount,
    },
  };
}

// ─── GET handlers ────────────────────────────────────────────────────────────

function detailHandler(req: MockRequest, db: MockDb, correlationId: string): BorrowerDetail {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const borrower = loadBorrowerForSession(db, session, id, correlationId);
  return projectDetail(db, borrower);
}

function loansHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): BorrowerLoansResponse {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const borrower = loadBorrowerForSession(db, session, id, correlationId);

  let apps = getApplicationsForBorrower(db, borrower.id);
  // LSP users only see loans owned by their LSP — a borrower may be visible
  // to multiple LSPs but each LSP only sees its own bookings.
  if (LSP_UI_ROLES.has(session.role)) {
    apps = apps.filter((a) => a.lspId === session.lspId);
  }
  apps.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));

  const loans: BorrowerLoanRow[] = apps.map((app) => {
    const lsp = db.lsps.get(app.lspId);
    const product = db.products.get(app.productId);
    return {
      applicationId: app.id,
      externalLoanId: app.externalLoanId,
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
  });
  return { loans };
}

// ─── Document access handler ─────────────────────────────────────────────────

const RecordDocumentAccessSchema = z.object({
  documentId: Uuid,
  action: z.enum(["VIEW", "PREVIEW", "DOWNLOAD"]),
  idempotencyKey: z.string().min(1).max(80),
});

/**
 * Map the contract's UI action verbs (`VIEW` | `PREVIEW` | `DOWNLOAD`) to the
 * canonical audit action enum (`VIEWED` | `DOWNLOADED` | ...). Both `VIEW`
 * and `PREVIEW` resolve to `VIEWED`; `DOWNLOAD` resolves to `DOWNLOADED`.
 */
function mapAccessAction(
  action: RecordDocumentAccessInput["action"],
): z.infer<typeof DocumentAccessAction> {
  if (action === "DOWNLOAD") return "DOWNLOADED";
  return "VIEWED";
}

function documentAccessHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): RecordDocumentAccessResponse {
  const session = requireSession(db, correlationId);
  if (!hasPermission(session.role, "LOAN_READ")) {
    throw new ForbiddenError(correlationId, "role cannot view documents");
  }

  const documentId = req.params?.["documentId"] ?? "";
  const parsed = RecordDocumentAccessSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(
      correlationId,
      "invalid document-access body",
      parsed.error.flatten(),
    );
  }
  if (parsed.data.documentId !== documentId) {
    throw new BadRequestError(correlationId, "path/body documentId mismatch");
  }

  const doc: LoanDocument | undefined = db.documents.get(documentId);
  if (!doc) {
    throw new NotFoundError(correlationId, `document ${documentId} not found`);
  }

  // Tenant scope: deny if the document's parent application is out of scope.
  const app = db.applications.get(doc.applicationId);
  if (!app) {
    throw new NotFoundError(correlationId, "document references a missing application");
  }
  if (LSP_UI_ROLES.has(session.role) && session.lspId !== app.lspId) {
    throw new ForbiddenError(correlationId, "document out of tenant scope");
  }

  const event: DocumentAccessEvent = {
    id: newIdempotencyKey(),
    documentId: doc.id,
    applicationId: app.id,
    action: mapAccessAction(parsed.data.action),
    actorId: session.userId,
    actorRole: session.role,
    correlationId,
    accessedAt: new Date().toISOString(),
  };
  db.auditDocumentAccess.push(event);

  return { auditId: event.id };
}

// ─── Drift-detection schemas for the dispatch parser ─────────────────────────

const VisibleLspSchema = z.object({ id: z.string(), name: z.string() });

const BorrowerDetailResponseSchema = z.object({
  borrower: z.unknown(),
  visibleLsps: z.array(VisibleLspSchema).readonly(),
  totals: z.object({
    openApplicationsCount: z.number().int().nonnegative(),
    closedApplicationsCount: z.number().int().nonnegative(),
    lifetimeDisbursedAmount: z.number().nonnegative(),
    activeOverdueAmount: z.number().nonnegative(),
  }),
});

const BorrowerLoanRowSchema = z.object({
  applicationId: z.string(),
  externalLoanId: z.string().nullable(),
  lspId: z.string(),
  lspName: z.string(),
  productId: z.string(),
  productName: z.string(),
  requestedAmount: z.number().nonnegative(),
  tenureMonths: z.number().int(),
  status: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
});

const BorrowerLoansResponseSchema = z.object({
  loans: z.array(BorrowerLoanRowSchema).readonly(),
});

const RecordDocumentAccessResponseSchema = z.object({
  auditId: Uuid,
});

// ─── Route registration (idempotent) ─────────────────────────────────────────

let registered = false;
export function registerBorrowerRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/borrowers/:id", detailHandler);
  registerRoute("GET", "/api/v1/borrowers/:id/loans", loansHandler);
  registerRoute("POST", "/api/v1/documents/:documentId/access", documentAccessHandler, {
    mutating: true,
  });
}

registerBorrowerRoutes();

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

export async function detail(id: string, opts?: RequestOptions): Promise<BorrowerDetail> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/borrowers/${id}`,
      headers: buildHeaders(opts),
    },
    BorrowerDetailResponseSchema,
  ) as Promise<BorrowerDetail>;
}

export async function loans(id: string, opts?: RequestOptions): Promise<BorrowerLoansResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/borrowers/${id}/loans`,
      headers: buildHeaders(opts),
    },
    BorrowerLoansResponseSchema,
  ) as Promise<BorrowerLoansResponse>;
}

export interface RecordDocumentAccessClientInput {
  action: RecordDocumentAccessInput["action"];
}

export async function recordDocumentAccess(
  documentId: string,
  input: RecordDocumentAccessClientInput,
  opts?: RequestOptions,
): Promise<RecordDocumentAccessResponse> {
  const idempotencyKey = opts?.idempotencyKey ?? newIdempotencyKey();
  const correlationId = opts?.correlationId ?? newCorrelationId();
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/documents/${documentId}/access`,
      body: { documentId, action: input.action, idempotencyKey },
      headers: {
        "Idempotency-Key": idempotencyKey,
        "X-Correlation-Id": correlationId,
      },
    },
    RecordDocumentAccessResponseSchema,
  );
}
