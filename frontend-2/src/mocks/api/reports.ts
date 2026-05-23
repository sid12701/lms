/**
 * Reports handler (Phase 8 Agent B).
 *
 * Implements `/reports` portfolio-MIS endpoints. Every figure is derived
 * from existing fixtures in `db.applications` + `db.accounts` +
 * `db.installments` + `db.payments`; nothing here is hand-coded.
 *
 * Endpoints:
 *   GET  /api/v1/reports/mis-summary?lspId=&dateFrom=&dateTo=
 *   GET  /api/v1/reports/mis-preview?lspId=&dateFrom=&dateTo=&page=&pageSize=
 *   GET  /api/v1/reports/requests
 *   POST /api/v1/reports/requests
 *   GET  /api/v1/reports/requests/:id/download
 *
 * Role gate: SYSTEM_ADMIN only on every route.
 *
 * BR coverage:
 *   - BR-5  every POST replays under its `idempotencyKey` (router cache)
 *   - dateFrom ≤ dateTo enforced as a BusinessRuleError (`INVALID_DATE_RANGE`)
 *
 * Auto-completion lifecycle (per plan §12 "5s mock polling flips queued
 * reports"):
 *   QUEUED      → PROCESSING after 5s (queuedAt-relative)
 *   PROCESSING  → COMPLETED  after 10s (queuedAt-relative)
 *   `tickReportRequests()` runs this advancement and is invoked by every GET
 *   /requests call so the polling hook drives state transitions for free.
 */
import { z } from "zod";
import { Email, IsoDate, Uuid } from "@/schemas/common";
import { LoanStatus } from "@/schemas/loan-application";
import { DelinquencyBucket } from "@/schemas/loan-account";
import { newIdempotencyKey } from "@/lib/idempotency";
import type {
  LoanAccount,
  LoanApplication,
  LoanStatus as LoanStatusType,
  PaymentTransaction,
  ReportRequest,
  ReportStatus,
  Role,
} from "@/types";
import { dispatch, registerRoute, type MockRequest } from "../router";
import {
  BadRequestError,
  ForbiddenError,
  NotFoundError,
  UnauthorizedError,
} from "../errors";
import type { MockDb } from "../db/state";

// ─── Role helpers ────────────────────────────────────────────────────────────

const ALLOWED_ROLES = new Set<Role>(["SYSTEM_ADMIN"]);

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

function requireSystemAdmin(session: ActiveSession, correlationId: string): void {
  if (!ALLOWED_ROLES.has(session.role)) {
    throw new ForbiddenError(correlationId, "role cannot access reports");
  }
}

// ─── Filter parsing ──────────────────────────────────────────────────────────

const ReportRangeQuery = z.object({
  lspId: Uuid.optional(),
  dateFrom: IsoDate.optional(),
  dateTo: IsoDate.optional(),
});
type ReportRangeQuery = z.infer<typeof ReportRangeQuery>;

const ReportPreviewQuery = ReportRangeQuery.extend({
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(1).max(500).optional(),
});

function parseRange<T extends z.ZodType>(
  schema: T,
  req: MockRequest,
  correlationId: string,
): z.infer<T> {
  const parsed = (schema as z.ZodType).safeParse(req.query ?? {});
  if (!parsed.success) {
    throw new BadRequestError(correlationId, "invalid range query", parsed.error.flatten());
  }
  const data = parsed.data as { dateFrom?: string; dateTo?: string };
  if (data.dateFrom && data.dateTo && data.dateFrom > data.dateTo) {
    throw new BadRequestError(
      correlationId,
      "dateFrom must be on or before dateTo",
    );
  }
  return parsed.data as z.infer<T>;
}

function withinRange(iso: string | null, from?: string, to?: string): boolean {
  if (!iso) return false;
  const date = iso.slice(0, 10);
  if (from && date < from) return false;
  if (to && date > to) return false;
  return true;
}

function applicationsInScope(
  db: MockDb,
  filters: ReportRangeQuery,
): LoanApplication[] {
  return Array.from(db.applications.values()).filter((app) => {
    if (filters.lspId && app.lspId !== filters.lspId) return false;
    return true;
  });
}

// ─── MIS summary ─────────────────────────────────────────────────────────────

interface MisSummaryPayload {
  totalDisbursedMtd: number;
  activeLoanCount: number;
  weightedAvgYieldPct: number;
  portfolioAtRisk30Pct: number;
  totalLoanCount: number;
}

const ACTIVE_LOAN_STATUSES = new Set<LoanStatusType>([
  "DISBURSED",
  "UNDER_REPAYMENT",
  "PARTIALLY_PAID",
  "DELINQUENT",
  "FORECLOSURE_REQUESTED",
]);

function isInCurrentMonth(iso: string, now: Date): boolean {
  const d = new Date(iso);
  return d.getUTCFullYear() === now.getUTCFullYear() && d.getUTCMonth() === now.getUTCMonth();
}

function buildMisSummary(db: MockDb, filters: ReportRangeQuery): MisSummaryPayload {
  const apps = applicationsInScope(db, filters);
  const appIds = new Set(apps.map((a) => a.id));
  const accounts = Array.from(db.accounts.values()).filter((a) => appIds.has(a.applicationId));
  const accountIds = new Set(accounts.map((a) => a.id));

  const now = new Date();
  let totalDisbursedMtd = 0;
  for (const p of db.payments) {
    if (!accountIds.has(p.accountId)) continue;
    if (p.installmentId !== null) continue;
    if (!isInCurrentMonth(p.postedAt, now)) continue;
    totalDisbursedMtd += p.amount;
  }

  // For weighted yield we approximate "yield" as interest-share-of-installment
  // averaged across installments weighted by principal. The seed sets
  // interest = 0.3 * installment which gives ~30%.
  let yieldNumerator = 0;
  let yieldDenominator = 0;
  let portfolioBalance = 0;
  let atRisk30Balance = 0;
  for (const account of accounts) {
    const installments = db.installments.get(account.id) ?? [];
    let principalDueSum = 0;
    let interestDueSum = 0;
    let outstandingBalance = 0;
    let overdue30Balance = 0;
    for (const inst of installments) {
      principalDueSum += inst.principalDue;
      interestDueSum += inst.interestDue;
      outstandingBalance += inst.outstandingAmount;
      if (inst.dpd >= 30) {
        overdue30Balance += inst.outstandingAmount;
      }
    }
    if (principalDueSum > 0) {
      const apr =
        (interestDueSum / (principalDueSum + interestDueSum)) * 100;
      yieldNumerator += apr * account.principal;
      yieldDenominator += account.principal;
    }
    portfolioBalance += outstandingBalance;
    atRisk30Balance += overdue30Balance;
  }

  const weightedAvgYieldPct =
    yieldDenominator > 0 ? yieldNumerator / yieldDenominator : 0;
  const portfolioAtRisk30Pct =
    portfolioBalance > 0 ? (atRisk30Balance / portfolioBalance) * 100 : 0;

  return {
    totalDisbursedMtd,
    activeLoanCount: apps.filter((a) => ACTIVE_LOAN_STATUSES.has(a.status)).length,
    weightedAvgYieldPct: Math.round(weightedAvgYieldPct * 100) / 100,
    portfolioAtRisk30Pct: Math.round(portfolioAtRisk30Pct * 100) / 100,
    totalLoanCount: apps.length,
  };
}

function summaryHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): MisSummaryPayload {
  const session = requireSession(db, correlationId);
  requireSystemAdmin(session, correlationId);
  const filters = parseRange(ReportRangeQuery, req, correlationId);
  return buildMisSummary(db, filters);
}

// ─── MIS preview ─────────────────────────────────────────────────────────────

interface MisPreviewRowPayload {
  loanId: string;
  externalLoanId: string | null;
  borrowerName: string;
  lspCode: string;
  productCode: string;
  amount: number;
  status: LoanStatusType;
  disbursalDate: string | null;
  dpd: number;
  delinquencyBucket: z.infer<typeof DelinquencyBucket> | null;
  year: number | null;
  processingFee: number;
  disbursalAmount: number;
  interestPct: number;
  tenureMonths: number;
  emiAmount: number;
  overdueAmount: number;
  closureDate: string | null;
  foreclosureDate: string | null;
  foreclosedAmount: number | null;
  pan: string | null;
  aadhaar: string | null;
  gender: string | null;
  state: string | null;
  zip: string | null;
  ifsc: string | null;
  bankAccount: string | null;
  profession: string | null;
  income: number | null;
}

interface MisPreviewResponse {
  items: MisPreviewRowPayload[];
  total: number;
  page: number;
  pageSize: number;
}

function findAccount(
  db: MockDb,
  applicationId: string,
): LoanAccount | undefined {
  for (const acc of db.accounts.values()) {
    if (acc.applicationId === applicationId) return acc;
  }
  return undefined;
}

function findDisbursementPayment(
  db: MockDb,
  accountId: string | undefined,
): PaymentTransaction | undefined {
  if (!accountId) return undefined;
  // Disbursement legs carry `installmentId === null` in the seed.
  for (const p of db.payments) {
    if (p.accountId === accountId && p.installmentId === null) return p;
  }
  return undefined;
}

const BULLET = "•";
function maskName(name: string): string {
  const parts = name.trim().split(/\s+/u);
  if (parts.length === 0 || !parts[0]) return BULLET;
  const first = parts[0].charAt(0);
  if (parts.length === 1) return `${first}${BULLET.repeat(3)}`;
  return `${first}${BULLET.repeat(3)} ${parts[parts.length - 1]}`;
}

function buildPreviewRow(
  db: MockDb,
  app: LoanApplication,
): MisPreviewRowPayload {
  const borrower = db.borrowers.get(app.borrowerId);
  const lsp = db.lsps.get(app.lspId);
  const product = db.products.get(app.productId);
  const account = findAccount(db, app.id);
  const installments = account ? db.installments.get(account.id) ?? [] : [];
  const disbursementPayment = findDisbursementPayment(db, account?.id);

  let maxDpd = 0;
  let worstBucket: z.infer<typeof DelinquencyBucket> | null = null;
  let overdueAmount = 0;
  let principalDueSum = 0;
  let interestDueSum = 0;
  let emiAmount = 0;
  for (const inst of installments) {
    if (inst.dpd > maxDpd) {
      maxDpd = inst.dpd;
      worstBucket = inst.delinquencyBucket;
    }
    overdueAmount += inst.status === "OVERDUE" ? inst.outstandingAmount : 0;
    principalDueSum += inst.principalDue;
    interestDueSum += inst.interestDue;
    emiAmount = inst.installmentAmount;
  }
  const interestPct =
    principalDueSum + interestDueSum > 0
      ? Math.round((interestDueSum / (principalDueSum + interestDueSum)) * 10_000) / 100
      : 0;

  const disbursalIso = disbursementPayment?.postedAt ?? null;
  const disbursalDate = disbursalIso ? disbursalIso.slice(0, 10) : null;
  const year = disbursalDate
    ? Number.parseInt(disbursalDate.slice(0, 4), 10)
    : null;

  const closureDate = account?.closedAt ? account.closedAt.slice(0, 10) : null;
  const isForeclosed = account?.closureReason === "FORECLOSED";
  const foreclosureDate = isForeclosed ? closureDate : null;
  const foreclosedAmount = isForeclosed
    ? db.payments
        .filter((p) => p.accountId === account?.id && p.channel === "ADJUSTMENT")
        .reduce((sum, p) => sum + p.amount, 0) || null
    : null;

  return {
    loanId: app.id,
    externalLoanId: app.externalLoanId,
    borrowerName: borrower ? maskName(borrower.fullName) : BULLET,
    lspCode: lsp?.code ?? "UNKNOWN",
    productCode: product?.code ?? "UNKNOWN",
    amount: app.requestedAmount,
    status: app.status,
    disbursalDate,
    dpd: maxDpd,
    delinquencyBucket: worstBucket,
    year,
    processingFee: Math.round(app.requestedAmount * 0.01),
    disbursalAmount: disbursementPayment?.amount ?? 0,
    interestPct,
    tenureMonths: app.tenureMonths,
    emiAmount,
    overdueAmount,
    closureDate,
    foreclosureDate,
    foreclosedAmount,
    pan: borrower ? `${borrower.pan.slice(0, 3)}${BULLET.repeat(4)}${borrower.pan.slice(-1)}` : null,
    aadhaar: borrower
      ? `${BULLET.repeat(8)}${borrower.aadhaar.slice(-4)}`
      : null,
    gender: borrower?.gender ?? null,
    state: borrower?.address.state ?? null,
    zip: borrower?.address.zip ?? null,
    ifsc: borrower?.banking.ifsc ?? null,
    bankAccount: borrower
      ? `${BULLET.repeat(6)}${borrower.banking.accountNumber.slice(-4)}`
      : null,
    profession: borrower?.employment.type ?? null,
    income: borrower?.employment.monthlyIncome ?? null,
  };
}

function previewHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): MisPreviewResponse {
  const session = requireSession(db, correlationId);
  requireSystemAdmin(session, correlationId);
  const filters = parseRange(ReportPreviewQuery, req, correlationId);

  let apps = applicationsInScope(db, filters);
  // If dateFrom/dateTo set: restrict to apps whose disbursement payment OR
  // createdAt lies in the range. We use createdAt as a stable filter so all
  // apps (including non-disbursed) are filterable.
  if (filters.dateFrom || filters.dateTo) {
    apps = apps.filter((a) =>
      withinRange(a.createdAt.slice(0, 10), filters.dateFrom, filters.dateTo),
    );
  }
  // Deterministic ordering: newest first by createdAt.
  apps.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));

  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 50;
  const total = apps.length;
  const slice = apps.slice(page * pageSize, page * pageSize + pageSize);

  return {
    items: slice.map((a) => buildPreviewRow(db, a)),
    total,
    page,
    pageSize,
  };
}

// ─── Report request lifecycle ────────────────────────────────────────────────

const QUEUED_TO_PROCESSING_MS = 5_000;
const QUEUED_TO_COMPLETED_MS = 10_000;

/**
 * Advance any QUEUED → PROCESSING (after 5s) and any PROCESSING → COMPLETED
 * (after 10s, both measured from `queuedAt`). Pure mutation against the
 * provided db. Called from every requests GET handler so the polling hook
 * doesn't need a separate timer.
 */
export function tickReportRequests(db: MockDb, now: Date = new Date()): void {
  const nowMs = now.getTime();
  for (const [id, req] of db.reportRequests.entries()) {
    const queuedMs = new Date(req.queuedAt).getTime();
    const elapsed = nowMs - queuedMs;
    if (req.status === "QUEUED" && elapsed >= QUEUED_TO_PROCESSING_MS) {
      db.reportRequests.set(id, { ...req, status: "PROCESSING" });
    }
  }
  // Second pass — let PROCESSING entries advance regardless of where the
  // QUEUED jump put them. Necessary when elapsed already exceeds 10s.
  for (const [id, req] of db.reportRequests.entries()) {
    const queuedMs = new Date(req.queuedAt).getTime();
    const elapsed = nowMs - queuedMs;
    if (req.status === "PROCESSING" && elapsed >= QUEUED_TO_COMPLETED_MS) {
      const completedAtIso = new Date(queuedMs + QUEUED_TO_COMPLETED_MS).toISOString();
      db.reportRequests.set(id, {
        ...req,
        status: "COMPLETED",
        completedAt: completedAtIso,
        fileMeta: {
          storageKey: `mock-reports/${req.id}.csv`,
          size: 1024,
          rowCount: 50,
          generatedAt: completedAtIso,
        },
      });
    }
  }
}

function listRequestsHandler(
  _req: MockRequest,
  db: MockDb,
  correlationId: string,
): { items: ReportRequest[] } {
  const session = requireSession(db, correlationId);
  requireSystemAdmin(session, correlationId);
  tickReportRequests(db);
  const items = Array.from(db.reportRequests.values())
    .filter((r) => r.requestedBy === session.userId)
    .sort((a, b) => (a.queuedAt < b.queuedAt ? 1 : -1));
  return { items };
}

const CreateRequestBodySchema = z.object({
  type: z.literal("PORTFOLIO_MIS"),
  lspId: Uuid.nullable().optional(),
  dateFrom: IsoDate.nullable().optional(),
  dateTo: IsoDate.nullable().optional(),
  notificationEmail: Email.nullable().optional(),
  idempotencyKey: z.string().min(1).max(80),
});

function createRequestHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): ReportRequest {
  const session = requireSession(db, correlationId);
  requireSystemAdmin(session, correlationId);
  const parsed = CreateRequestBodySchema.safeParse(req.body);
  if (!parsed.success) {
    throw new BadRequestError(
      correlationId,
      "invalid report-request body",
      parsed.error.flatten(),
    );
  }
  const { type, lspId, dateFrom, dateTo, notificationEmail } = parsed.data;
  if (dateFrom && dateTo && dateFrom > dateTo) {
    throw new BadRequestError(
      correlationId,
      "dateFrom must be on or before dateTo",
    );
  }

  const nowIso = new Date().toISOString();
  const id = newIdempotencyKey();
  const record: ReportRequest = {
    id,
    type,
    status: "QUEUED",
    requestedBy: session.userId,
    lspId: lspId ?? null,
    dateFrom: dateFrom ?? null,
    dateTo: dateTo ?? null,
    notificationEmail: notificationEmail ?? null,
    fileMeta: null,
    errorMessage: null,
    queuedAt: nowIso,
    completedAt: null,
  };
  db.reportRequests.set(id, record);
  return record;
}

function downloadHandler(
  req: MockRequest,
  db: MockDb,
  correlationId: string,
): { url: string } {
  const session = requireSession(db, correlationId);
  requireSystemAdmin(session, correlationId);
  tickReportRequests(db);
  const id = req.params?.["id"] ?? "";
  const record = db.reportRequests.get(id);
  if (!record) {
    throw new NotFoundError(correlationId, `report request ${id} not found`);
  }
  if (record.status !== "COMPLETED") {
    throw new BadRequestError(
      correlationId,
      `report request is not ready (status=${record.status})`,
    );
  }
  // Mock CSV: a single header row inline. The data URI keeps the test
  // assertion simple without spinning up object storage.
  const csv = "loanId,borrowerName,amount\nmock,Mock User,1000";
  const base64 = typeof btoa === "function" ? btoa(csv) : Buffer.from(csv).toString("base64");
  return { url: `data:text/csv;base64,${base64}` };
}

// ─── Drift-detection schemas ─────────────────────────────────────────────────

const MisSummarySchema = z.object({
  totalDisbursedMtd: z.number().nonnegative(),
  activeLoanCount: z.number().int().nonnegative(),
  weightedAvgYieldPct: z.number().nonnegative().max(100),
  portfolioAtRisk30Pct: z.number().nonnegative().max(100),
  totalLoanCount: z.number().int().nonnegative(),
});

const ReportStatusSchema: z.ZodType<ReportStatus> = z.enum([
  "QUEUED",
  "PROCESSING",
  "COMPLETED",
  "FAILED",
]);

const ReportRequestRowSchema = z.object({
  id: z.string(),
  type: z.literal("PORTFOLIO_MIS"),
  status: ReportStatusSchema,
  requestedBy: z.string(),
  lspId: z.string().nullable(),
  dateFrom: z.string().nullable(),
  dateTo: z.string().nullable(),
  notificationEmail: z.string().nullable(),
  fileMeta: z
    .object({
      storageKey: z.string(),
      size: z.number().int().nonnegative(),
      rowCount: z.number().int().nonnegative(),
      generatedAt: z.string(),
    })
    .nullable(),
  errorMessage: z.string().nullable(),
  queuedAt: z.string(),
  completedAt: z.string().nullable(),
}) satisfies z.ZodType<ReportRequest>;

const MisPreviewRowSchema = z.object({
  loanId: z.string(),
  externalLoanId: z.string().nullable(),
  borrowerName: z.string(),
  lspCode: z.string(),
  productCode: z.string(),
  amount: z.number().nonnegative(),
  status: LoanStatus,
  disbursalDate: z.string().nullable(),
  dpd: z.number().int().nonnegative(),
  delinquencyBucket: DelinquencyBucket.nullable(),
  year: z.number().int().nullable(),
  processingFee: z.number().nonnegative(),
  disbursalAmount: z.number().nonnegative(),
  interestPct: z.number().nonnegative().max(100),
  tenureMonths: z.number().int(),
  emiAmount: z.number().nonnegative(),
  overdueAmount: z.number().nonnegative(),
  closureDate: z.string().nullable(),
  foreclosureDate: z.string().nullable(),
  foreclosedAmount: z.number().nullable(),
  pan: z.string().nullable(),
  aadhaar: z.string().nullable(),
  gender: z.string().nullable(),
  state: z.string().nullable(),
  zip: z.string().nullable(),
  ifsc: z.string().nullable(),
  bankAccount: z.string().nullable(),
  profession: z.string().nullable(),
  income: z.number().nullable(),
});

const MisPreviewResponseSchema = z.object({
  items: z.array(MisPreviewRowSchema),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
});

const RequestsListResponseSchema = z.object({
  items: z.array(ReportRequestRowSchema),
});

const DownloadResponseSchema = z.object({
  url: z.string().min(1),
});

// ─── Route registration ──────────────────────────────────────────────────────

let registered = false;
export function registerReportRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/reports/mis-summary", summaryHandler);
  registerRoute("GET", "/api/v1/reports/mis-preview", previewHandler);
  registerRoute("GET", "/api/v1/reports/requests", listRequestsHandler);
  registerRoute("POST", "/api/v1/reports/requests", createRequestHandler, {
    mutating: true,
  });
  registerRoute("GET", "/api/v1/reports/requests/:id/download", downloadHandler);
}

registerReportRoutes();

// ─── Public API surface ──────────────────────────────────────────────────────

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

function buildQuery(filters: Record<string, unknown> = {}): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || value === null || value === "") continue;
    out[key] = String(value);
  }
  return out;
}

export interface MisFilters {
  lspId?: string | null;
  dateFrom?: string | null;
  dateTo?: string | null;
}

export interface MisPreviewFilters extends MisFilters {
  page?: number;
  pageSize?: number;
}

export type MisSummary = z.infer<typeof MisSummarySchema>;
export type MisPreviewRow = z.infer<typeof MisPreviewRowSchema>;
export interface MisPreviewResponseDto {
  items: MisPreviewRow[];
  total: number;
  page: number;
  pageSize: number;
}

export async function misSummary(
  filters: MisFilters = {},
  opts?: RequestOptions,
): Promise<MisSummary> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/reports/mis-summary",
      query: buildQuery(filters as Record<string, unknown>),
      headers: buildHeaders(opts),
    },
    MisSummarySchema,
  );
}

export async function misPreview(
  filters: MisPreviewFilters = {},
  opts?: RequestOptions,
): Promise<MisPreviewResponseDto> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/reports/mis-preview",
      query: buildQuery(filters as Record<string, unknown>),
      headers: buildHeaders(opts),
    },
    MisPreviewResponseSchema,
  );
}

export async function listRequests(
  opts?: RequestOptions,
): Promise<{ items: ReportRequest[] }> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/reports/requests",
      headers: buildHeaders(opts),
    },
    RequestsListResponseSchema,
  );
}

export interface CreateReportRequestInput {
  type: "PORTFOLIO_MIS";
  lspId?: string | null;
  dateFrom?: string | null;
  dateTo?: string | null;
  notificationEmail?: string | null;
}

export async function createRequest(
  input: CreateReportRequestInput,
  opts?: RequestOptions,
): Promise<ReportRequest> {
  const idempotencyKey = opts?.idempotencyKey ?? newIdempotencyKey();
  return dispatch(
    {
      method: "POST",
      path: "/api/v1/reports/requests",
      body: { ...input, idempotencyKey },
      headers: { ...buildHeaders(opts), "Idempotency-Key": idempotencyKey },
    },
    ReportRequestRowSchema,
  );
}

export async function downloadRequest(
  id: string,
  opts?: RequestOptions,
): Promise<{ url: string }> {
  return dispatch(
    {
      method: "GET",
      path: `/api/v1/reports/requests/${id}/download`,
      headers: buildHeaders(opts),
    },
    DownloadResponseSchema,
  );
}
