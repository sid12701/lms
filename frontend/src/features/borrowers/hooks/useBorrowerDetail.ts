/**
 * TanStack Query wrapper around `fetchBorrowerDetail`.
 *
 * Cache key: `["borrower", id]` — distinct from the activity / loans caches
 * so the detail can refetch independently after a PII-reveal mutation
 * appends to `auditPiiReveal`.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchBorrowerDetail } from "../api";
import type { BorrowerDetail } from "../types";

const BORROWER_DETAIL_QUERY_KEY = "borrower" as const;

/** Build the canonical query key — exported so mutation hooks can invalidate. */
export function borrowerDetailQueryKey(id: string) {
  return [BORROWER_DETAIL_QUERY_KEY, id] as const;
}

export function useBorrowerDetail(id: string): UseQueryResult<BorrowerDetail, Error> {
  return useQuery({
    queryKey: borrowerDetailQueryKey(id),
    queryFn: () => fetchBorrowerDetail(id),
    staleTime: 30_000,
    enabled: typeof id === "string" && id.length > 0,
  });
}
