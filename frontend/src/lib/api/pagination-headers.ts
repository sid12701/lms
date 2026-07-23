const PAGINATION_TOTAL_COUNT_HEADER = "X-Total-Count";
const PAGINATION_LIMIT_HEADER = "X-Limit";
const PAGINATION_OFFSET_HEADER = "X-Offset";

export interface PaginationHeaders {
  totalCount: number | null;
  limit: number | null;
  offset: number | null;
}

function readIntHeader(headers: Headers, name: string): number | null {
  const raw = headers.get(name);
  if (raw == null || raw.trim() === "") return null;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) ? parsed : null;
}

/** Read Spring `PaginationResponseBuilder` headers from a list response. */
export function readPaginationHeaders(headers: Headers): PaginationHeaders {
  return {
    totalCount: readIntHeader(headers, PAGINATION_TOTAL_COUNT_HEADER),
    limit: readIntHeader(headers, PAGINATION_LIMIT_HEADER),
    offset: readIntHeader(headers, PAGINATION_OFFSET_HEADER),
  };
}
