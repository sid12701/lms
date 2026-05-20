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
import type { HomeKpis } from "./types";

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
 * Fetch the home dashboard payload for the active session. The handler
 * branches on the caller's role and returns either the internal-user shape
 * or the LSP-user shape; the discriminated union forces consumers to handle
 * both.
 */
export async function fetchHomeKpis(): Promise<HomeKpis> {
  return dispatch(
    {
      method: "GET",
      path: "/api/v1/home/kpis",
    },
    HomeKpisSchema,
  );
}
