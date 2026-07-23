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
 * backend's offset/limit.
 */
import { buildQueryPath, requestJsonWithHeaders } from "@/lib/api/http-client";
import { readPaginationHeaders } from "@/lib/api/pagination-headers";
import { apiLoanStatus } from "@/lib/loan-application-status";
import type {
  LoanApplicationListFilters,
  LoanApplicationListItem,
  LoanApplicationListResponse,
} from "./types";

const BACKEND_BASE = "/api/v1/internal/ops/loan-applications";

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
    status: apiLoanStatus(payload.status),
    createdAt: payload.createdAt ?? new Date().toISOString(),
    updatedAt: payload.createdAt ?? new Date().toISOString(),
  };
}

function backendQueryFromFilters(
  filters: LoanApplicationListFilters,
): Record<string, string | number | undefined> {
  const pageSize = filters.pageSize ?? 25;
  const offset = (filters.page ?? 0) * pageSize;
  const firstStatus = filters.status && filters.status.length > 0 ? filters.status[0] : undefined;
  return {
    lspId: filters.lspId,
    productId: filters.productId,
    status: firstStatus,
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
  const { data, headers } = await requestJsonWithHeaders<BackendApplicationResponse[]>(path);
  const pagination = readPaginationHeaders(headers);
  const items = data.map(toListItem);
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;
  return { items, total: pagination.totalCount ?? items.length, page, pageSize };
}
