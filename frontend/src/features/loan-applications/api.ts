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
import { buildQueryPath, requestJson } from "@/lib/api/http-client";
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
// legacy detail-page renderers — they should not be sent to the backend.
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

export async function fetchLoanApplications(
  filters: LoanApplicationListFilters,
): Promise<LoanApplicationListResponse> {
  const path = buildQueryPath(BACKEND_BASE, backendQueryFromFilters(filters));
  const payload = await requestJson<BackendApplicationResponse[] | BackendListEnvelope>(path);
  const items = Array.isArray(payload)
    ? payload.map(toListItem)
    : (payload.items ?? []).map(toListItem);
  const total = Array.isArray(payload) ? items.length : (payload.totalCount ?? items.length);
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;
  return { items, total, page, pageSize };
}
