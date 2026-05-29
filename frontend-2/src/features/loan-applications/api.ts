/**
 * Loan-applications list, wired to the live backend.
 *
 * Backend contract: `LoanApplicationOpsController` (SYSTEM_ADMIN /
 * OPS_USER) under `/api/v1/internal/ops/loan-applications`. The detail
 * and per-tab endpoints exist on the same controller but are not yet
 * adapted onto the frontend's projection — see
 * docs/INTEGRATION-STATUS.md.
 *
 * Pagination is translated from the frontend's page/pageSize to the
 * backend's offset/limit. The backend's status enum is a superset of
 * the older statuses surfaced by the frontend; unknown values fall
 * through to `INITIATED` so the table keeps rendering.
 */
import { ApiError, buildQueryPath, requestJson } from "@/lib/api/http-client";
import { dispatch } from "@/mocks/router";
import { z } from "zod";
import { loadStoredSession } from "@/lib/api/session-storage";
import { LoanStatus } from "@/schemas/loan-application";
import type {
  LoanApplicationListFilters,
  LoanApplicationListItem,
  LoanApplicationListResponse,
} from "./types";

const BACKEND_BASE = "/api/v1/internal/ops/loan-applications";

// Gap #11 — pass through the backend's canonical 10-status enum directly.
// The legacy frontend-only values (INITIATED, INVALIDATED, UNDER_REVIEW,
// KYC_PENDING, DOCS_PENDING, DISBURSEMENT_IN_PROGRESS, APPROVED, etc.) are
// folded into the canonical statuses below for backward compatibility with
// older mocks and legacy detail-page renderers — they should not be sent
// to the backend.
const STATUS_PASS_THROUGH = new Set<LoanApplicationListItem["status"]>([
  "INITIALIZED",
  "AWAITING_APPROVAL",
  "APPROVED_PENDING_DISBURSAL",
  "REJECTED",
  "DISBURSEMENT_RETRY",
  "INVALID",
  "DISBURSED",
  "UNDER_REPAYMENT",
  "CLOSED",
  "FORECLOSED",
]);

export function mapBackendStatus(
  status: string | null | undefined,
): LoanApplicationListItem["status"] {
  if (!status) return "INITIALIZED";
  if (STATUS_PASS_THROUGH.has(status as LoanApplicationListItem["status"])) {
    return status as LoanApplicationListItem["status"];
  }
  // Backward compatibility: rows that pre-date Gap #11 still carry the old
  // status names. Fold them onto the canonical 10 so the table keeps
  // rendering after migration; these branches can be removed once the DB is
  // fully migrated forward.
  switch (status) {
    case "PAYMENT_REINITIATION":
      return "DISBURSEMENT_RETRY";
    case "INITIATED":
      return "INITIALIZED";
    case "INVALIDATED":
      return "INVALID";
    case "APPROVED":
      return "APPROVED_PENDING_DISBURSAL";
    default:
      return "INITIALIZED";
  }
}

function mapFrontendStatus(value: LoanApplicationListItem["status"]): string {
  return mapFrontendStatusToBackend(value);
}

/**
 * Reverse-map a frontend LoanStatus value into the backend
 * `LoanApplicationStatus` enum. The canonical 10 statuses are
 * pass-through; legacy frontend-only values fold to their canonical
 * equivalents.
 */
export function mapFrontendStatusToBackend(value: LoanApplicationListItem["status"]): string {
  switch (value) {
    case "INITIATED":
      return "INITIALIZED";
    case "INVALIDATED":
      return "INVALID";
    case "DISBURSEMENT_IN_PROGRESS":
      return "DISBURSEMENT_RETRY";
    case "APPROVED":
      return "APPROVED_PENDING_DISBURSAL";
    default:
      return value;
  }
}

interface BackendApplicationResponse {
  id: string;
  borrowerId: string;
  borrowerFullName: string;
  lspId: string;
  lspCode: string;
  lspName: string;
  productId: string;
  productCode: string;
  productName: string;
  externalLoanId: string | null;
  accountNumber: string | null;
  sourceChannel: string | null;
  requestedAmount: number | null;
  tenureMonths: number | null;
  status: string;
  createdAt: string | null;
}

interface BackendListEnvelope {
  items?: BackendApplicationResponse[];
  totalCount?: number;
  offset?: number;
  limit?: number;
}

function toListItem(payload: BackendApplicationResponse): LoanApplicationListItem {
  return {
    id: payload.id,
    externalLoanId: payload.externalLoanId,
    accountNumber: payload.accountNumber ?? null,
    borrowerId: payload.borrowerId,
    borrowerNameMasked: payload.borrowerFullName,
    lspId: payload.lspId,
    lspName: payload.lspName,
    productId: payload.productId,
    productName: payload.productName,
    requestedAmount: Number(payload.requestedAmount ?? 0),
    tenureMonths: Number(payload.tenureMonths ?? 0),
    status: mapBackendStatus(payload.status),
    createdAt: payload.createdAt ?? new Date().toISOString(),
    updatedAt: payload.createdAt ?? new Date().toISOString(),
  };
}

export function backendQueryFromFilters(
  filters: LoanApplicationListFilters,
): Record<string, string | number | undefined> {
  const pageSize = filters.pageSize ?? 25;
  const offset = (filters.page ?? 0) * pageSize;
  // The backend takes a single status, not a comma list — pick the first.
  const firstStatus = filters.status && filters.status.length > 0 ? filters.status[0] : undefined;
  const status = firstStatus ? mapFrontendStatus(firstStatus) : undefined;
  return {
    lspId: filters.lspId,
    productId: filters.productId,
    status,
    q: filters.q,
    lspLoanId: filters.lspLoanId,
    bhawLoanId: filters.bhawLoanId,
    disbursalDateFrom: filters.disbursalDateFrom,
    disbursalDateTo: filters.disbursalDateTo,
    offset,
    limit: pageSize,
    paginationDetails: "ON",
  };
}

const FALLBACK_ITEM_SCHEMA = z.object({
  id: z.string().min(1),
  externalLoanId: z.string().nullable(),
  accountNumber: z.string().nullable(),
  borrowerId: z.string().min(1),
  borrowerNameMasked: z.string().min(1),
  lspId: z.string().min(1),
  lspName: z.string().min(1),
  productId: z.string().min(1),
  productName: z.string().min(1),
  requestedAmount: z.number().nonnegative(),
  tenureMonths: z.number().int().nonnegative(),
  status: LoanStatus,
  createdAt: z.string().min(1),
  updatedAt: z.string().min(1),
});

export const LoanApplicationListResponseSchema: z.ZodType<LoanApplicationListResponse> = z.object({
  items: z.array(FALLBACK_ITEM_SCHEMA).readonly(),
  total: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  pageSize: z.number().int().positive(),
});

export function buildLoanApplicationsQuery(filters: LoanApplicationListFilters): string {
  const params = new URLSearchParams();
  if (filters.q && filters.q.trim() !== "") params.set("q", filters.q);
  if (filters.lspLoanId && filters.lspLoanId.trim() !== "")
    params.set("lspLoanId", filters.lspLoanId);
  if (filters.bhawLoanId && filters.bhawLoanId.trim() !== "")
    params.set("bhawLoanId", filters.bhawLoanId);
  if (filters.disbursalDateFrom) params.set("disbursalDateFrom", filters.disbursalDateFrom);
  if (filters.disbursalDateTo) params.set("disbursalDateTo", filters.disbursalDateTo);
  if (filters.status && filters.status.length > 0) {
    params.set("status", filters.status.join(","));
  }
  if (filters.lspId) params.set("lspId", filters.lspId);
  if (filters.productId) params.set("productId", filters.productId);
  if (typeof filters.page === "number") params.set("page", String(filters.page));
  if (typeof filters.pageSize === "number") params.set("pageSize", String(filters.pageSize));
  if (filters.sortBy) params.set("sortBy", filters.sortBy);
  if (filters.sortDir) params.set("sortDir", filters.sortDir);
  return params.toString();
}

export async function fetchLoanApplications(
  filters: LoanApplicationListFilters,
): Promise<LoanApplicationListResponse> {
  const session = loadStoredSession();
  const role = session?.user.role;
  const isInternal = role === "SYSTEM_ADMIN" || role === "OPS_USER" || role === "PRODUCT_ADMIN";

  if (isInternal) {
    try {
      const path = buildQueryPath(BACKEND_BASE, backendQueryFromFilters(filters));
      const payload = await requestJson<BackendApplicationResponse[] | BackendListEnvelope>(path);
      const items = Array.isArray(payload)
        ? payload.map(toListItem)
        : (payload.items ?? []).map(toListItem);
      const total = Array.isArray(payload) ? items.length : (payload.totalCount ?? items.length);
      const pageSize = filters.pageSize ?? 25;
      const page = filters.page ?? 0;
      return { items, total, page, pageSize };
    } catch (error) {
      if (!(error instanceof ApiError) || error.status >= 500) throw error;
      // Fall through to mock on 4xx — typically backend down in dev.
    }
  }

  // LSP roles and unauthenticated dev sessions still go to the mock.
  const query = buildLoanApplicationsQuery(filters);
  const path = `/api/v1/loan-applications${query ? `?${query}` : ""}`;
  return dispatch({ method: "GET", path }, LoanApplicationListResponseSchema);
}
