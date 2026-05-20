/**
 * Loan applications API client — thin wrapper around the mock router.
 *
 * The contract is owned by `./types.ts`; this module registers a runtime
 * Zod parser that mirrors `LoanApplicationListResponse` so the router's
 * drift detection fires when agent A's handler returns an unexpected
 * shape. The wrapper only owns shape + transport — the actual data
 * lives in `@/mocks/api/loan-applications.ts` (agent A).
 */
import { z } from "zod";
import { dispatch } from "@/mocks/router";
import { LoanStatus } from "@/schemas/loan-application";
import type {
  LoanApplicationListFilters,
  LoanApplicationListResponse,
} from "./types";

// ─── Runtime parsers (mirror `types.ts` exactly) ────────────────────────────

const LoanApplicationListItemSchema = z.object({
  id: z.string().min(1),
  externalLoanId: z.string().nullable(),
  borrowerId: z.string().min(1),
  borrowerNameMasked: z.string().min(1),
  lspId: z.string().min(1),
  lspName: z.string().min(1),
  productId: z.string().min(1),
  productName: z.string().min(1),
  requestedAmount: z.number().nonnegative(),
  tenureMonths: z.number().int().nonnegative(),
  status: LoanStatus,
  assignedTo: z.string().nullable(),
  assignedToName: z.string().nullable(),
  createdAt: z.string().min(1),
  updatedAt: z.string().min(1),
});

export const LoanApplicationListResponseSchema: z.ZodType<LoanApplicationListResponse> = z.object({
  items: z.array(LoanApplicationListItemSchema).readonly(),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
});

// ─── Public surface ─────────────────────────────────────────────────────────

/**
 * Serialise filters into a stable wire-format query string.
 *
 * - Scalar values use `set(...)`.
 * - Arrays (e.g. `status`) are comma-joined into a single param so the
 *   resulting URL stays short even when many statuses are selected.
 * - `undefined`, `null`, and empty values are omitted.
 *
 * The wire format is independent of `useUrlFilters` (which uses repeated
 * params) — agent A's handler is responsible for parsing both forms.
 */
export function buildLoanApplicationsQuery(filters: LoanApplicationListFilters): string {
  const params = new URLSearchParams();
  if (filters.q && filters.q.trim() !== "") params.set("q", filters.q);
  if (filters.status && filters.status.length > 0) {
    params.set("status", filters.status.join(","));
  }
  if (filters.lspId) params.set("lspId", filters.lspId);
  if (filters.productId) params.set("productId", filters.productId);
  if (filters.assignedTo) params.set("assignedTo", filters.assignedTo);
  if (typeof filters.page === "number") params.set("page", String(filters.page));
  if (typeof filters.pageSize === "number") {
    params.set("pageSize", String(filters.pageSize));
  }
  if (filters.sortBy) params.set("sortBy", filters.sortBy);
  if (filters.sortDir) params.set("sortDir", filters.sortDir);
  return params.toString();
}

/**
 * Fetch the loan-applications list for the active session, filtered + paged
 * server-side. The handler enforces tenant scoping (LSP_UI sees its own
 * applications; SYSTEM_ADMIN / OPS_USER see all).
 */
export async function fetchLoanApplications(
  filters: LoanApplicationListFilters,
): Promise<LoanApplicationListResponse> {
  const query = buildLoanApplicationsQuery(filters);
  const path = `/api/v1/loan-applications${query ? `?${query}` : ""}`;
  return dispatch(
    {
      method: "GET",
      path,
    },
    LoanApplicationListResponseSchema,
  );
}
