/**
 * Borrowers directory list client — live backend only.
 */
import { z } from "zod";
import { requestJsonWithHeaders } from "@/lib/api/http-client";
import { readPaginationHeaders } from "@/lib/api/pagination-headers";
import type { BorrowerListFilters, BorrowerListResponse, BorrowerSummary } from "./list-types";

const BACKEND_BASE = "/api/v1/internal/admin/borrowers";

const DEFAULT_PAGE_SIZE = 50;

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

  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
  const page = filters.page ?? 0;
  params.set("offset", String(page * pageSize));
  params.set("limit", String(pageSize));
  params.set("paginationDetails", "ON");
  return params.toString();
}

export async function fetchBorrowersList(
  filters: BorrowerListFilters,
): Promise<BorrowerListResponse> {
  const query = buildBorrowersListQuery(filters);
  const path = `${BACKEND_BASE}${query ? `?${query}` : ""}`;
  const { data, headers } = await requestJsonWithHeaders<BorrowerSummary[]>(path);
  const items = BorrowerSummaryArraySchema.parse(data);
  const pagination = readPaginationHeaders(headers);
  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
  const page = filters.page ?? 0;
  return {
    items,
    total: pagination.totalCount ?? items.length,
    page,
    pageSize,
  };
}
