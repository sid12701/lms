/**
 * Borrowers handler (Phase 6).
 *
 * Implements every endpoint the borrower-detail surface + the Phase 5
 * documents tab consume. The cross-agent contract lives in
 * `@/features/borrowers/types`; this file mirrors that contract one-to-one.
 *
 * Endpoints:
 *   GET  /api/v1/borrowers/:id                       → BorrowerDetail
 *   GET  /api/v1/borrowers/:id/loans                 → BorrowerLoansResponse
 *   GET  /api/v1/borrowers/:id/activity              → BorrowerActivityResponse
 *   POST /api/v1/borrowers/:id/pii-reveal            → RecordPiiRevealResponse
 *   POST /api/v1/documents/:documentId/access        → RecordDocumentAccessResponse
 *
 * The `documents/:documentId/access` route is co-located here because
 * Phase 6 (borrower Activity feed) and Phase 5 (documents tab) both consume
 * it; keeping one handler avoids a duplicate-route registration race.
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
 * BR coverage:
 *   - BR-5  every POST replays under its `idempotencyKey` for 30s (router cache)
 *   - BR-7  PII reveal + document access write to their audit streams
 */
import { z } from "zod";
import { Iso8601, Uuid } from "@/schemas/common";
import { PiiFieldName, DocumentAccessAction } from "@/schemas/audit";
import { hasPermission } from "@/lib/permissions";
import { newIdempotencyKey } from "@/lib/idempotency";
import { STATUS_META } from "@/lib/lifecycle";
import type {
  DocumentAccessEvent,
  LoanAccount,
  LoanApplication,
  LoanDocument,
  PiiRevealEvent,
  RepaymentInstallment,
  Role,
} from "@/types";
import type { Borrower } from "@/schemas/borrower";
import type {
  BorrowerActivityEntry,
  BorrowerActivityResponse,
  BorrowerDetail,
  BorrowerLoanRow,
  BorrowerLoansResponse,
  RecordDocumentAccessInput,
  RecordDocumentAccessResponse,
  RecordPiiRevealInput,
  RecordPiiRevealResponse,
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

function activityHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): BorrowerActivityResponse {
  const session = requireSession(db, correlationId);
  const id = req.params?.["id"] ?? "";
  const borrower = loadBorrowerForSession(db, session, id, correlationId);

  const apps = getApplicationsForBorrower(db, borrower.id);
  const appIds = new Set(apps.map((a) => a.id));

  const entries: BorrowerActivityEntry[] = [];

  // Application audit events — every transition on borrower-owned apps.
  for (const e of db.auditApplication) {
    if (appIds.has(e.applicationId)) {
      entries.push({ kind: "APPLICATION", event: e });
    }
  }

  // PII reveal events — schema uses `subjectId` + `subjectType` (no
  // `subjectBorrowerId` field). Filter to BORROWER + matching subjectId.
  for (const e of db.auditPiiReveal) {
    if (e.subjectType === "BORROWER" && e.subjectId === borrower.id) {
      entries.push({ kind: "PII_REVEAL", event: e });
    }
  }

  // Document access events — match by the doc's parent application.
  for (const e of db.auditDocumentAccess) {
    if (appIds.has(e.applicationId)) {
      entries.push({ kind: "DOCUMENT_ACCESS", event: e });
    }
  }

  // Sort by event timestamp desc — fields differ per stream so resolve per kind.
  const tsOf = (entry: BorrowerActivityEntry): string => {
    if (entry.kind === "APPLICATION") return entry.event.createdAt;
    if (entry.kind === "PII_REVEAL") return entry.event.revealedAt;
    return entry.event.accessedAt;
  };
  entries.sort((a, b) => (tsOf(a) < tsOf(b) ? 1 : -1));

  return { entries };
}

// ─── PII reveal handler ──────────────────────────────────────────────────────

const RecordPiiRevealSchema = z.object({
  borrowerId: Uuid,
  field: PiiFieldName,
  reason: z.string().min(1).max(500),
  idempotencyKey: z.string().min(1).max(80),
});

function fieldValueFor(borrower: Borrower, field: z.infer<typeof PiiFieldName>): string {
  switch (field) {
    case "PAN":
      return borrower.pan;
    case "AADHAAR":
      return borrower.aadhaar;
    case "MOBILE":
      return borrower.mobile;
    case "ACCOUNT_NUMBER":
      return borrower.banking.accountNumber;
    case "EMAIL":
      return borrower.email ?? "";
  }
}

function piiRevealHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): RecordPiiRevealResponse {
  const session = requireSession(db, correlationId);
  // BR-7: only roles that can read loan data may reveal PII. LOAN_READ
  // is the closest fine-grained permission; SYSTEM_ADMIN/OPS_USER/LSP_*
  // all carry it. PRODUCT_ADMIN does too via the permissions table.
  if (!hasPermission(session.role, "LOAN_READ")) {
    throw new ForbiddenError(correlationId, "role cannot reveal PII");
  }

  const id = req.params?.["id"] ?? "";
  const parsed = RecordPiiRevealSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid pii-reveal body", parsed.error.flatten());
  }
  if (parsed.data.borrowerId !== id) {
    throw new BadRequestError(correlationId, "path/body borrowerId mismatch");
  }

  const borrower = loadBorrowerForSession(db, session, id, correlationId);

  const event: PiiRevealEvent = {
    id: newIdempotencyKey(),
    subjectType: "BORROWER",
    subjectId: borrower.id,
    fieldName: parsed.data.field,
    actorId: session.userId,
    actorRole: session.role,
    reason: parsed.data.reason,
    correlationId,
    revealedAt: new Date().toISOString(),
  };
  db.auditPiiReveal.push(event);

  return {
    value: fieldValueFor(borrower, parsed.data.field),
    auditId: event.id,
  };
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

const ActivityEntrySchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("APPLICATION"), event: z.object({ createdAt: Iso8601 }).passthrough() }),
  z.object({ kind: z.literal("PII_REVEAL"), event: z.object({ revealedAt: Iso8601 }).passthrough() }),
  z.object({
    kind: z.literal("DOCUMENT_ACCESS"),
    event: z.object({ accessedAt: Iso8601 }).passthrough(),
  }),
]);

const BorrowerActivityResponseSchema = z.object({
  entries: z.array(ActivityEntrySchema).readonly(),
});

const RecordPiiRevealResponseSchema = z.object({
  value: z.string(),
  auditId: Uuid,
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
  registerRoute("GET", "/api/v1/borrowers/:id/activity", activityHandler);
  registerRoute("POST", "/api/v1/borrowers/:id/pii-reveal", piiRevealHandler, {
    mutating: true,
  });
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

export async function activity(
  id: string,
  opts?: RequestOptions,
): Promise<BorrowerActivityResponse> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/borrowers/${id}/activity`,
      headers: buildHeaders(opts),
    },
    BorrowerActivityResponseSchema,
  ) as Promise<BorrowerActivityResponse>;
}

export interface RecordPiiRevealClientInput {
  field: RecordPiiRevealInput["field"];
  reason: string;
}

export async function recordPiiReveal(
  borrowerId: string,
  input: RecordPiiRevealClientInput,
  opts?: RequestOptions,
): Promise<RecordPiiRevealResponse> {
  const idempotencyKey = opts?.idempotencyKey ?? newIdempotencyKey();
  const correlationId = opts?.correlationId ?? newCorrelationId();
  return dispatch(
    {
      method: "POST",
      path: `/api/v1/borrowers/${borrowerId}/pii-reveal`,
      body: { borrowerId, field: input.field, reason: input.reason, idempotencyKey },
      headers: {
        "Idempotency-Key": idempotencyKey,
        "X-Correlation-Id": correlationId,
      },
    },
    RecordPiiRevealResponseSchema,
  );
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
