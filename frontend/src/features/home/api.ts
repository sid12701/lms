/**
 * Home API client — calls the live backend dashboard endpoints and validates
 * responses against runtime Zod parsers that mirror `./types.ts`.
 */
import { z } from "zod";
import { AlertSeverity, AlertSubjectType } from "@/schemas/alert";
import { LoanStatus } from "@/schemas/loan-application";
import { DelinquencyBucket } from "@/schemas/loan-account";
import { ApiError, requestJson } from "@/lib/api/http-client";
import { loadStoredSession } from "@/lib/api/session-storage";
import { apiLoanStatus } from "@/lib/loan-application-status";
import type {
  ApplicationsByStatusBucket,
  DpdBucketSummary,
  HomeAlertSummary,
  HomeKpis,
  InternalHomeKpis,
} from "./types";
import type { LoanStatus as LoanStatusType } from "@/types";

// ─── Runtime parsers (mirror `types.ts` exactly) ────────────────────────────

const ApplicationsByStatusBucketSchema = z.object({
  status: LoanStatus,
  count: z.number().int().nonnegative(),
});

const DpdBucketSummarySchema = z.object({
  bucket: DelinquencyBucket,
  count: z.number().int().nonnegative(),
});

const HomeRecentApplicationSchema = z.object({
  id: z.string().min(1),
  externalLoanId: z.string().nullable(),
  borrowerNameMasked: z.string().min(1),
  lspName: z.string().min(1),
  productName: z.string().min(1),
  status: LoanStatus,
  requestedAmount: z.number().nonnegative(),
  createdAt: z.string().min(1),
});

const HomeAlertSummarySchema = z.object({
  id: z.string().min(1),
  severity: AlertSeverity,
  title: z.string().min(1),
  subjectType: AlertSubjectType,
  subjectId: z.string().min(1),
  createdAt: z.string().min(1),
});

const InternalHomeKpisSchema = z.object({
  applicationsAwaitingApproval: z.number().int().nonnegative(),
  applicationsInDisbursement: z.number().int().nonnegative(),
  mtdDisbursedAmount: z.number().nonnegative(),
  overdueLoansCount: z.number().int().nonnegative(),
  overdueAmount: z.number().nonnegative(),
  avgApprovalTatHours: z.number().nullable(),
  applicationsByStatus: z.array(ApplicationsByStatusBucketSchema).readonly(),
  dpdBuckets: z.array(DpdBucketSummarySchema).readonly(),
  recentApplications: z.array(HomeRecentApplicationSchema).readonly(),
  openAlerts: z.array(HomeAlertSummarySchema).readonly(),
});

const LspHomeKpisSchema = z.object({
  myActiveApplications: z.number().int().nonnegative(),
  myInDisbursement: z.number().int().nonnegative(),
  myMtdDisbursedAmount: z.number().nonnegative(),
  myOverdueLoansCount: z.number().int().nonnegative(),
  recentApplications: z.array(HomeRecentApplicationSchema).readonly(),
  openAlerts: z.array(HomeAlertSummarySchema).readonly(),
});

/**
 * Discriminated union — must match the `HomeKpis` exported type in
 * `./types.ts`. Keep these two in sync.
 */
export const HomeKpisSchema: z.ZodType<HomeKpis> = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("internal"), data: InternalHomeKpisSchema }),
  z.object({ kind: z.literal("lsp"), data: LspHomeKpisSchema }),
]);

// ─── Public surface ─────────────────────────────────────────────────────────

/** Backend `LoanDelinquencyBucket` → frontend chart bucket ids. */
const BACKEND_DPD_TO_FE: Record<string, DelinquencyBucket> = {
  CURRENT: "B0",
  DPD_1_30: "B1_30",
  DPD_31_60: "B31_60",
  DPD_61_90: "B61_90",
  DPD_90_PLUS: "B90_PLUS",
};

const DPD_BUCKETS_IN_ORDER: readonly DelinquencyBucket[] = [
  "B0",
  "B1_30",
  "B31_60",
  "B61_90",
  "B90_PLUS",
];

/**
 * Backend home-dashboard response shape (matches
 * `HomeDashboardService.HomeDashboardSummary`).
 */
export interface BackendHomeOverview {
  totalDisbursedAmount: number;
  totalOutstandingAmount: number;
  dpd90PlusAmount: number;
  dpd90PlusLoanCount: number;
  applicationsAwaitingApproval: number;
  applicationsInDisbursement: number;
  avgApprovalTatHours: number | null;
  applicationsByStatus: ReadonlyArray<{ status: string; count: number }>;
  dpdBuckets: ReadonlyArray<{ bucket: string; count: number }>;
  openAlerts: number;
  openAlertSummaries: ReadonlyArray<{
    id: string;
    severity: string;
    title: string;
    subjectType: string;
    subjectId: string;
    createdAt: string;
  }>;
  priorityAccounts: ReadonlyArray<{
    applicationId: string;
    externalLoanId: string | null;
    customerName: string;
    lspCode: string;
    principalAmount: number;
    overdueAmount: number;
    daysPastDue: number;
    loanStatusDisplay: string;
  }>;
}

function safeAlertSubjectType(value: string): HomeAlertSummary["subjectType"] {
  const parsed = AlertSubjectType.safeParse(value);
  return parsed.success ? parsed.data : "SYSTEM";
}

function mapDpdBuckets(
  rows: ReadonlyArray<{ bucket: string; count: number }>,
): readonly DpdBucketSummary[] {
  const counts = new Map<DelinquencyBucket, number>();
  for (const bucket of DPD_BUCKETS_IN_ORDER) {
    counts.set(bucket, 0);
  }
  for (const row of rows) {
    const mapped = BACKEND_DPD_TO_FE[row.bucket] ?? "B0";
    counts.set(mapped, row.count);
  }
  return DPD_BUCKETS_IN_ORDER.map((bucket) => ({
    bucket,
    count: counts.get(bucket) ?? 0,
  }));
}

function mapApplicationsByStatus(
  rows: ReadonlyArray<{ status: string; count: number }>,
): readonly ApplicationsByStatusBucket[] {
  return rows.map((row) => ({
    status: apiLoanStatus(row.status) as LoanStatusType,
    count: row.count,
  }));
}

/** Gap #7 — maps the live backend overview onto the internal home projection. */
export function mapBackendHomeOverviewToInternalKpis(
  overview: BackendHomeOverview,
): InternalHomeKpis {
  const recentApplications = overview.priorityAccounts.map((account) => ({
    id: account.applicationId,
    externalLoanId: account.externalLoanId,
    borrowerNameMasked: account.customerName,
    lspName: account.lspCode,
    productName: account.loanStatusDisplay,
    status: apiLoanStatus(account.loanStatusDisplay) as LoanStatusType,
    requestedAmount: account.principalAmount,
    createdAt: new Date().toISOString(),
  }));
  const openAlerts: HomeAlertSummary[] = overview.openAlertSummaries.map((alert) => ({
    id: alert.id,
    severity: AlertSeverity.safeParse(alert.severity).success
      ? AlertSeverity.parse(alert.severity)
      : "MEDIUM",
    title: alert.title,
    subjectType: safeAlertSubjectType(alert.subjectType),
    subjectId: alert.subjectId || alert.id,
    createdAt: alert.createdAt,
  }));
  return {
    applicationsAwaitingApproval: overview.applicationsAwaitingApproval,
    applicationsInDisbursement: overview.applicationsInDisbursement,
    mtdDisbursedAmount: overview.totalDisbursedAmount,
    overdueLoansCount: overview.dpd90PlusLoanCount,
    overdueAmount: overview.dpd90PlusAmount,
    avgApprovalTatHours: overview.avgApprovalTatHours,
    applicationsByStatus: mapApplicationsByStatus(overview.applicationsByStatus),
    dpdBuckets: mapDpdBuckets(overview.dpdBuckets),
    recentApplications,
    openAlerts,
  };
}

/**
 * Fetch the home dashboard payload for the active session.
 *
 * For SYSTEM_ADMIN sessions the backend's
 * `/api/v1/internal/home/overview` is queried and mapped onto the
 * `InternalHomeKpis` projection (Gap #7 — all six KPI fields populated).
 *
 * Gap #8: only SYSTEM_ADMIN may load home KPIs. Other roles use their
 * primary landing route and must not call this client.
 */
export async function fetchHomeKpis(): Promise<HomeKpis> {
  const session = loadStoredSession();
  if (!session || session.user.role !== "SYSTEM_ADMIN") {
    throw new ApiError(
      "Home dashboard is only available to system administrators.",
      403,
      "",
      "FORBIDDEN",
    );
  }
  const overview = await requestJson<BackendHomeOverview>("/api/v1/internal/home/overview");
  return { kind: "internal", data: mapBackendHomeOverviewToInternalKpis(overview) };
}
