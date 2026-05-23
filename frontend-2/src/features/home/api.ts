/**
 * Home API client — thin wrapper around the mock router for the Phase 4
 * dashboard endpoint.
 *
 * The contract is owned by `./types.ts`; this module just registers a runtime
 * Zod parser that mirrors `HomeKpis` so the router's drift detection fires
 * when the handler returns something unexpected. The wrapper itself only
 * concerns itself with shape + transport — actual business logic lives in
 * `@/mocks/api/home.ts` (agent A).
 */
import { z } from "zod";
import { dispatch } from "@/mocks/router";
import { AlertSeverity, AlertSubjectType } from "@/schemas/alert";
import { LoanStatus } from "@/schemas/loan-application";
import { DelinquencyBucket } from "@/schemas/loan-account";
import { ApiError, requestJson } from "@/lib/api/http-client";
import { loadStoredSession } from "@/lib/api/session-storage";
import type { HomeKpis, InternalHomeKpis } from "./types";

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
  assignedToName: z.string().nullable(),
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

/**
 * Backend home-dashboard response shape (matches
 * `HomeDashboardService.HomeDashboardSummary`). The backend only exposes the
 * SYSTEM_ADMIN-scoped overview today; LSP-scoped + PRODUCT_ADMIN-scoped home
 * views fall back to the mock for now (see docs/INTEGRATION-STATUS.md).
 */
interface BackendHomeOverview {
  totalDisbursedAmount: number;
  totalOutstandingAmount: number;
  dpd90PlusAmount: number;
  dpd90PlusLoanCount: number;
  lspBreakdown: Array<{ lspId: string; lspCode: string; lspName: string }>;
  priorityAccounts: Array<{
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

function backendToInternalHomeKpis(overview: BackendHomeOverview): InternalHomeKpis {
  const recentApplications = overview.priorityAccounts.map((account) => ({
    id: account.applicationId,
    externalLoanId: account.externalLoanId,
    borrowerNameMasked: account.customerName,
    lspName: account.lspCode,
    productName: account.loanStatusDisplay,
    status: "DISBURSED" as const,
    requestedAmount: account.principalAmount,
    createdAt: new Date().toISOString(),
    assignedToName: null,
  }));
  return {
    applicationsAwaitingApproval: 0,
    applicationsInDisbursement: 0,
    mtdDisbursedAmount: overview.totalDisbursedAmount,
    overdueLoansCount: overview.dpd90PlusLoanCount,
    overdueAmount: overview.dpd90PlusAmount,
    avgApprovalTatHours: null,
    applicationsByStatus: [],
    dpdBuckets: [],
    recentApplications,
    openAlerts: [],
  };
}

/**
 * Fetch the home dashboard payload for the active session.
 *
 * For SYSTEM_ADMIN sessions the backend's
 * `/api/v1/internal/home/overview` is queried and mapped onto the
 * `InternalHomeKpis` projection (gap fields default to 0/empty — see
 * docs/INTEGRATION-STATUS.md).
 *
 * Other roles fall back to the in-process mock because the backend does
 * not expose a per-role home aggregate yet.
 */
export async function fetchHomeKpis(): Promise<HomeKpis> {
  const session = loadStoredSession();
  if (session?.user.role === "SYSTEM_ADMIN") {
    try {
      const overview = await requestJson<BackendHomeOverview>(
        "/api/v1/internal/home/overview",
      );
      return { kind: "internal", data: backendToInternalHomeKpis(overview) };
    } catch (error) {
      if (!(error instanceof ApiError) || error.status >= 500) {
        throw error;
      }
      // Fall through to mock on 4xx — most often when the backend isn't running
      // in this dev session. Lets the rest of the surface stay clickable.
    }
  }
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/home/kpis",
    },
    HomeKpisSchema,
  );
}
