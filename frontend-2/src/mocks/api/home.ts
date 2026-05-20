/**
 * Home dashboard handler.
 *
 * One endpoint:
 *   GET /api/v1/home/kpis  →  HomeKpis  (role-discriminated payload)
 *
 * Roles:
 *   - SYSTEM_ADMIN | OPS_USER | PRODUCT_ADMIN  → "internal" payload
 *   - LSP_UI_READ  | LSP_UI_WRITE              → "lsp" payload, scoped to lspId
 *   - LSP_API_CLIENT                            → 401 (UI surface only)
 *
 * Every figure is derived from the mock db — no constants in the payload.
 *
 * Per Phase 4 gate (frontend-implementation-plan.md §12):
 *   "KPIs derive from mock DB; no static decoration."
 */
import { z } from "zod";
import { Iso8601, Uuid } from "@/schemas/common";
import { LoanStatus } from "@/schemas/loan-application";
import { AlertSeverity, AlertSubjectType } from "@/schemas/alert";
import type {
  ApplicationsByStatusBucket,
  DpdBucketSummary,
  HomeAlertSummary,
  HomeKpis,
  HomeRecentApplication,
  InternalHomeKpis,
  LspHomeKpis,
} from "@/features/home/types";
import type { DelinquencyBucket } from "@/schemas/loan-account";
import type {
  LoanAccount,
  LoanApplication,
  LoanStatus as LoanStatusType,
  OperationalAlert,
  PaymentTransaction,
  RepaymentInstallment,
} from "@/types";
import { dispatch, registerRoute, type MockRequest } from "../router";
import { UnauthorizedError, newCorrelationId } from "../errors";
import type { MockDb } from "../db/state";

// ─── Schemas (used only for dispatch parser drift-detection) ─────────────────

const HomeRecentApplicationSchema = z.object({
  id: Uuid,
  externalLoanId: z.string().nullable(),
  borrowerNameMasked: z.string(),
  lspName: z.string(),
  productName: z.string(),
  status: LoanStatus,
  requestedAmount: z.number().nonnegative(),
  createdAt: Iso8601,
  assignedToName: z.string().nullable(),
});

const HomeAlertSummarySchema = z.object({
  id: Uuid,
  severity: AlertSeverity,
  title: z.string(),
  subjectType: AlertSubjectType,
  subjectId: z.string(),
  createdAt: Iso8601,
});

const ApplicationsByStatusBucketSchema = z.object({
  status: LoanStatus,
  count: z.number().int().nonnegative(),
});

const DpdBucketSummarySchema = z.object({
  bucket: z.enum(["B0", "B1_30", "B31_60", "B61_90", "B90_PLUS"]),
  count: z.number().int().nonnegative(),
});

const InternalKpisSchema = z.object({
  applicationsAwaitingApproval: z.number().int().nonnegative(),
  applicationsInDisbursement: z.number().int().nonnegative(),
  mtdDisbursedAmount: z.number().nonnegative(),
  overdueLoansCount: z.number().int().nonnegative(),
  overdueAmount: z.number().nonnegative(),
  avgApprovalTatHours: z.number().nonnegative().nullable(),
  applicationsByStatus: z.array(ApplicationsByStatusBucketSchema),
  dpdBuckets: z.array(DpdBucketSummarySchema),
  recentApplications: z.array(HomeRecentApplicationSchema),
  openAlerts: z.array(HomeAlertSummarySchema),
});

const LspKpisSchema = z.object({
  myActiveApplications: z.number().int().nonnegative(),
  myInDisbursement: z.number().int().nonnegative(),
  myMtdDisbursedAmount: z.number().nonnegative(),
  myOverdueLoansCount: z.number().int().nonnegative(),
  recentApplications: z.array(HomeRecentApplicationSchema),
  openAlerts: z.array(HomeAlertSummarySchema),
});

export const HomeKpisSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("internal"), data: InternalKpisSchema }),
  z.object({ kind: z.literal("lsp"), data: LspKpisSchema }),
]);

// ─── Helpers ─────────────────────────────────────────────────────────────────

const INTERNAL_ROLES = new Set(["SYSTEM_ADMIN", "OPS_USER", "PRODUCT_ADMIN"]);
const LSP_ROLES = new Set(["LSP_UI_READ", "LSP_UI_WRITE"]);

const DISBURSING_STATUSES = new Set<LoanStatusType>([
  "APPROVED_PENDING_DISBURSAL",
  "DISBURSEMENT_IN_PROGRESS",
]);

const ACTIVE_STATUSES = new Set<LoanStatusType>([
  "INITIATED",
  "KYC_PENDING",
  "DOCS_PENDING",
  "UNDER_REVIEW",
  "AWAITING_APPROVAL",
  "APPROVED",
  "APPROVED_PENDING_DISBURSAL",
  "DISBURSEMENT_IN_PROGRESS",
  "DISBURSED",
  "UNDER_REPAYMENT",
  "PARTIALLY_PAID",
  "DELINQUENT",
  "FORECLOSURE_REQUESTED",
]);

const BULLET = "•••";

/**
 * Mask a borrower's display name.
 *
 * "Aanya Sharma" → "A••• Sharma"
 *
 * Keeps the first character of the first name + the last word intact so an
 * operator can still scan rows; everything else is replaced with a bullet
 * block. Single-word names degrade gracefully to "A•••".
 */
export function maskBorrowerName(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return BULLET;
  const parts = trimmed.split(/\s+/u);
  const first = parts[0] ?? "";
  if (parts.length === 1) {
    return `${first.charAt(0)}${BULLET}`;
  }
  const last = parts[parts.length - 1] ?? "";
  return `${first.charAt(0)}${BULLET} ${last}`;
}

function isInCurrentMonth(iso: string, now: Date): boolean {
  const d = new Date(iso);
  return d.getUTCFullYear() === now.getUTCFullYear() && d.getUTCMonth() === now.getUTCMonth();
}

function isInLastNDays(iso: string, n: number, now: Date): boolean {
  const d = new Date(iso).getTime();
  const cutoff = now.getTime() - n * 86_400_000;
  return d >= cutoff && d <= now.getTime();
}

/**
 * `mtdDisbursedAmount` strategy:
 * sum `amount` of payments whose `installmentId === null` (treated as the
 * outbound disbursement leg in the seed) AND whose `postedAt` is in the
 * current calendar month. Falls back to summing principals of accounts
 * created this month if no disbursement payments exist.
 */
function computeMtdDisbursedAmount(
  payments: ReadonlyArray<PaymentTransaction>,
  accounts: ReadonlyArray<LoanAccount>,
  now: Date,
  lspApplicationIds?: Set<string>,
  accountToLspApp?: Map<string, string>,
): number {
  // Disbursement payments — single-leg, null installmentId in our seed.
  let sum = 0;
  for (const p of payments) {
    if (p.installmentId !== null) continue;
    if (!isInCurrentMonth(p.postedAt, now)) continue;
    if (lspApplicationIds && accountToLspApp) {
      const appId = accountToLspApp.get(p.accountId);
      if (!appId || !lspApplicationIds.has(appId)) continue;
    }
    sum += p.amount;
  }
  if (sum > 0) return sum;
  // Fallback: account principals approved this month.
  for (const a of accounts) {
    if (!isInCurrentMonth(a.approvedAt, now)) continue;
    if (lspApplicationIds && !lspApplicationIds.has(a.applicationId)) continue;
    sum += a.principal;
  }
  return sum;
}

interface OverdueTotals {
  count: number;
  amount: number;
}

function isInstallmentOverdue(inst: RepaymentInstallment, today: string): boolean {
  if (inst.status === "OVERDUE") return true;
  if (inst.status === "PAID") return false;
  return inst.dueDate < today;
}

function computeOverdue(
  installments: Map<string, RepaymentInstallment[]>,
  accountIdsInScope: Set<string> | null,
  today: string,
): OverdueTotals {
  let count = 0;
  let amount = 0;
  for (const [accountId, list] of installments.entries()) {
    if (accountIdsInScope && !accountIdsInScope.has(accountId)) continue;
    let acctHasOverdue = false;
    for (const inst of list) {
      if (!isInstallmentOverdue(inst, today)) continue;
      acctHasOverdue = true;
      amount += inst.outstandingAmount > 0 ? inst.outstandingAmount : inst.installmentAmount;
    }
    if (acctHasOverdue) count += 1;
  }
  return { count, amount };
}

/**
 * `avgApprovalTatHours` — average hours between the AWAITING_APPROVAL and
 * APPROVED_PENDING_DISBURSAL audit events per application within the last
 * 30 days. Returns null when no qualifying transitions are available.
 */
function computeAvgApprovalTatHours(
  db: MockDb,
  applicationIdsInScope: Set<string> | null,
  now: Date,
): number | null {
  const awaitingByApp = new Map<string, number>();
  const approvedByApp = new Map<string, number>();
  for (const ev of db.auditApplication) {
    if (applicationIdsInScope && !applicationIdsInScope.has(ev.applicationId)) continue;
    if (!isInLastNDays(ev.createdAt, 30, now)) continue;
    const ts = new Date(ev.createdAt).getTime();
    if (ev.toStatus === "AWAITING_APPROVAL") {
      // Earliest awaiting event wins.
      const prev = awaitingByApp.get(ev.applicationId);
      if (prev === undefined || ts < prev) awaitingByApp.set(ev.applicationId, ts);
    } else if (ev.toStatus === "APPROVED_PENDING_DISBURSAL") {
      const prev = approvedByApp.get(ev.applicationId);
      if (prev === undefined || ts < prev) approvedByApp.set(ev.applicationId, ts);
    }
  }
  let total = 0;
  let n = 0;
  for (const [appId, awaitingTs] of awaitingByApp.entries()) {
    const approvedTs = approvedByApp.get(appId);
    if (approvedTs === undefined || approvedTs < awaitingTs) continue;
    total += (approvedTs - awaitingTs) / 3_600_000;
    n += 1;
  }
  if (n === 0) return null;
  return total / n;
}

function buildApplicationsByStatus(
  apps: ReadonlyArray<LoanApplication>,
): ApplicationsByStatusBucket[] {
  const counts = new Map<LoanStatusType, number>();
  for (const a of apps) {
    counts.set(a.status, (counts.get(a.status) ?? 0) + 1);
  }
  return Array.from(counts.entries()).map(([status, count]) => ({ status, count }));
}

/**
 * Count distinct loan accounts per delinquency bucket. Each account is
 * assigned to the WORST (highest-DPD) bucket among its installments.
 *
 * Returns a zero-filled array in stable order so the chart's X-axis is
 * deterministic even when a bucket has no loans.
 */
const DPD_BUCKETS_IN_ORDER: readonly DelinquencyBucket[] = [
  "B0",
  "B1_30",
  "B31_60",
  "B61_90",
  "B90_PLUS",
];

function buildDpdBuckets(
  installmentsMap: ReadonlyMap<string, ReadonlyArray<RepaymentInstallment>>,
  accountIds: ReadonlySet<string>,
): DpdBucketSummary[] {
  const order = new Map(DPD_BUCKETS_IN_ORDER.map((b, i) => [b, i] as const));
  const counts = new Map<DelinquencyBucket, number>();
  for (const b of DPD_BUCKETS_IN_ORDER) counts.set(b, 0);

  for (const accountId of accountIds) {
    const installments = installmentsMap.get(accountId) ?? [];
    let worst: DelinquencyBucket = "B0";
    let worstIdx = 0;
    for (const inst of installments) {
      const idx = order.get(inst.delinquencyBucket) ?? 0;
      if (idx > worstIdx) {
        worstIdx = idx;
        worst = inst.delinquencyBucket;
      }
    }
    counts.set(worst, (counts.get(worst) ?? 0) + 1);
  }

  return DPD_BUCKETS_IN_ORDER.map((bucket) => ({
    bucket,
    count: counts.get(bucket) ?? 0,
  }));
}

function projectRecentApplications(
  db: MockDb,
  apps: ReadonlyArray<LoanApplication>,
  limit: number,
): HomeRecentApplication[] {
  return [...apps]
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
    .slice(0, limit)
    .map((a) => {
      const borrower = db.borrowers.get(a.borrowerId);
      const lsp = db.lsps.get(a.lspId);
      const product = db.products.get(a.productId);
      const assignedTo = a.assignedTo ? db.users.get(a.assignedTo) : null;
      return {
        id: a.id,
        externalLoanId: a.externalLoanId,
        borrowerNameMasked: borrower ? maskBorrowerName(borrower.fullName) : BULLET,
        lspName: lsp?.name ?? "Unknown LSP",
        productName: product?.name ?? "Unknown product",
        status: a.status,
        requestedAmount: a.requestedAmount,
        createdAt: a.createdAt,
        assignedToName: assignedTo?.username ?? null,
      };
    });
}

/**
 * `openAlerts` — top 5 OPEN alerts by createdAt desc.
 *
 * Note: alerts do NOT carry an lspId in the schema, so for LSP callers the
 * full open-alerts feed is returned. If a future iteration tags alerts with
 * a tenant id, scope here.
 */
function projectOpenAlerts(alerts: ReadonlyArray<OperationalAlert>, limit: number): HomeAlertSummary[] {
  return [...alerts]
    .filter((a) => a.status === "OPEN")
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
    .slice(0, limit)
    .map((a) => ({
      id: a.id,
      severity: a.severity,
      title: a.title,
      subjectType: a.subjectType,
      subjectId: a.subjectId,
      createdAt: a.createdAt,
    }));
}

// ─── Handlers ────────────────────────────────────────────────────────────────

function homeKpisHandler(_req: MockRequest, db: MockDb, correlationId: string): HomeKpis {
  if (!db.currentSession) {
    throw new UnauthorizedError(correlationId, "no active session");
  }
  const user = db.users.get(db.currentSession.userId);
  if (!user) {
    throw new UnauthorizedError(correlationId, "user no longer exists");
  }

  const now = new Date();
  const today = now.toISOString().slice(0, 10);

  if (INTERNAL_ROLES.has(user.role)) {
    return { kind: "internal", data: buildInternal(db, now, today) };
  }
  if (LSP_ROLES.has(user.role)) {
    if (!user.lspId) {
      throw new UnauthorizedError(correlationId, "LSP user missing lspId");
    }
    return { kind: "lsp", data: buildLsp(db, user.lspId, now, today) };
  }
  // LSP_API_CLIENT and any other future role: UI surface unavailable.
  throw new UnauthorizedError(correlationId, `role ${user.role} cannot access the home dashboard`);
}

function buildInternal(db: MockDb, now: Date, today: string): InternalHomeKpis {
  const apps = Array.from(db.applications.values());
  const accounts = Array.from(db.accounts.values());

  const applicationsAwaitingApproval = apps.filter((a) => a.status === "AWAITING_APPROVAL").length;
  const applicationsInDisbursement = apps.filter((a) => DISBURSING_STATUSES.has(a.status)).length;
  const mtdDisbursedAmount = computeMtdDisbursedAmount(db.payments, accounts, now);
  const overdue = computeOverdue(db.installments, null, today);
  const avgApprovalTatHours = computeAvgApprovalTatHours(db, null, now);

  const allAccountIds = new Set(accounts.map((a) => a.id));

  return {
    applicationsAwaitingApproval,
    applicationsInDisbursement,
    mtdDisbursedAmount,
    overdueLoansCount: overdue.count,
    overdueAmount: overdue.amount,
    avgApprovalTatHours,
    applicationsByStatus: buildApplicationsByStatus(apps),
    dpdBuckets: buildDpdBuckets(db.installments, allAccountIds),
    recentApplications: projectRecentApplications(db, apps, 8),
    openAlerts: projectOpenAlerts(Array.from(db.alerts.values()), 5),
  };
}

function buildLsp(db: MockDb, lspId: string, now: Date, today: string): LspHomeKpis {
  const apps = Array.from(db.applications.values()).filter((a) => a.lspId === lspId);
  const appIds = new Set(apps.map((a) => a.id));

  const accounts = Array.from(db.accounts.values()).filter((acc) => appIds.has(acc.applicationId));
  const accountIds = new Set(accounts.map((acc) => acc.id));
  const accountToApp = new Map(accounts.map((acc) => [acc.id, acc.applicationId] as const));

  const myActiveApplications = apps.filter((a) => ACTIVE_STATUSES.has(a.status)).length;
  const myInDisbursement = apps.filter((a) => DISBURSING_STATUSES.has(a.status)).length;
  const myMtdDisbursedAmount = computeMtdDisbursedAmount(
    db.payments,
    accounts,
    now,
    appIds,
    accountToApp,
  );
  const overdue = computeOverdue(db.installments, accountIds, today);

  return {
    myActiveApplications,
    myInDisbursement,
    myMtdDisbursedAmount,
    myOverdueLoansCount: overdue.count,
    recentApplications: projectRecentApplications(db, apps, 8),
    openAlerts: projectOpenAlerts(Array.from(db.alerts.values()), 5),
  };
}

// ─── Route registration ──────────────────────────────────────────────────────

let registered = false;
export function registerHomeRoutes(): void {
  if (registered) return;
  registered = true;
  registerRoute("GET", "/api/v1/home/kpis", homeKpisHandler);
}

registerHomeRoutes();

// ─── Public API surface ──────────────────────────────────────────────────────

interface RequestOptions {
  correlationId?: string;
}

function buildHeaders(opts: RequestOptions = {}): Record<string, string> {
  const headers: Record<string, string> = {};
  if (opts.correlationId) headers["X-Correlation-Id"] = opts.correlationId;
  return headers;
}

export async function kpis(opts?: RequestOptions): Promise<HomeKpis> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/home/kpis",
      headers: {
        ...buildHeaders(opts),
        "X-Correlation-Id": opts?.correlationId ?? newCorrelationId(),
      },
    },
    HomeKpisSchema,
  );
}
