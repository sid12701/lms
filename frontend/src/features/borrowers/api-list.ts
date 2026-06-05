/**
 * Borrowers directory list client — live backend only.
 */
import { z } from "zod";
import { requestJson } from "@/lib/api/http-client";
import type { BorrowerListFilters, BorrowerListResponse, BorrowerSummary } from "./list-types";

const BACKEND_BASE = "/api/v1/internal/admin/borrowers";

const DEFAULT_PAGE_SIZE = 25;

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

export async function fetchBorrowersList(
  filters: BorrowerListFilters,
): Promise<BorrowerListResponse> {
  const query = buildBorrowersListQuery(filters);
  const path = `${BACKEND_BASE}${query ? `?${query}` : ""}`;
  const body = await requestJson<BorrowerSummary[]>(path);
  const items = BorrowerSummaryArraySchema.parse(body);
  return toResponse(items, filters);
}
