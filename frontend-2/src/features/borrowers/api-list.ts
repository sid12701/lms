/**
 * Borrowers directory list client.
 *
 * For internal sessions (SYSTEM_ADMIN / OPS_USER) we call the live backend
 * at `/api/v1/internal/admin/borrowers`. PII is masked server-side
 * (see `BorrowerAdminController.maskAadhar`) so the frontend never holds
 * raw aadhar values. For non-internal / unauthenticated dev sessions we
 * fall through to the mock router at `/api/v1/borrowers`.
 *
 * v1 paginates client-side: we fetch the matching set in one call and
 * report `total = items.length`. The backend already supports
 * offset/limit headers, so when the directory grows we can swap to
 * header-driven totals without changing the page contract.
 */
import { z } from "zod";
import { requestJson } from "@/lib/api/http-client";
import { dispatch } from "@/mocks/router";
import { loadStoredSession } from "@/lib/api/session-storage";
import type { BorrowerListFilters, BorrowerListResponse, BorrowerSummary } from "./list-types";

const BACKEND_BASE = "/api/v1/internal/admin/borrowers";
const MOCK_BASE = "/api/v1/borrowers";

const DEFAULT_PAGE_SIZE = 25;

function isInternalSession(): boolean {
  const role = loadStoredSession()?.user.role;
  return role === "SYSTEM_ADMIN" || role === "OPS_USER";
}

// ─── Runtime parsing (drift detection on the wire) ────────────────────────────

const BorrowerSummarySchema: z.ZodType<BorrowerSummary> = z.object({
  id: z.string().min(1),
  fullName: z.string().min(1),
  pan: z.string().min(1),
  mobile: z.string().min(1),
  email: z.string().nullable(),
  city: z.string().nullable(),
  state: z.string().nullable(),
  aadharNumberMasked: z.string().nullable(),
  visibleLspIds: z.array(z.string()).readonly().default([]),
}) as unknown as z.ZodType<BorrowerSummary>;

const BorrowerSummaryArraySchema = z.array(BorrowerSummarySchema);

// ─── Query string builder ─────────────────────────────────────────────────────

export function buildBorrowersListQuery(filters: BorrowerListFilters): string {
  const params = new URLSearchParams();
  const trimmedQ = filters.q?.trim();
  if (trimmedQ && trimmedQ.length > 0) params.set("q", trimmedQ);

  const paginate = typeof filters.page === "number" || typeof filters.pageSize === "number";
  if (paginate) {
    const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
    const page = filters.page ?? 0;
    params.set("offset", String(page * pageSize));
    params.set("limit", String(pageSize));
    params.set("paginationDetails", "ON");
  }
  return params.toString();
}

function toResponse(
  items: readonly BorrowerSummary[],
  filters: BorrowerListFilters,
): BorrowerListResponse {
  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
  const page = filters.page ?? 0;
  return {
    items,
    total: items.length,
    page,
    pageSize,
  };
}

// ─── Public surface ───────────────────────────────────────────────────────────

export async function fetchBorrowersList(
  filters: BorrowerListFilters,
): Promise<BorrowerListResponse> {
  const query = buildBorrowersListQuery(filters);

  if (isInternalSession()) {
    const path = `${BACKEND_BASE}${query ? `?${query}` : ""}`;
    const body = await requestJson<BorrowerSummary[]>(path);
    const items = BorrowerSummaryArraySchema.parse(body);
    return toResponse(items, filters);
  }

  // LSP-role: mock-router demo path (#78).
  // The mock router matches paths literally (no query-string stripping),
  // so we keep the path clean and pass the search term via `req.query`
  // which the list handler reads.
  const trimmedQ = filters.q?.trim();
  const mockQuery = trimmedQ ? { q: trimmedQ } : undefined;
  const body = (await dispatch(
    { method: "GET", path: MOCK_BASE, query: mockQuery },
    BorrowerSummaryArraySchema,
  )) as unknown as readonly BorrowerSummary[];
  return toResponse(body, filters);
}
