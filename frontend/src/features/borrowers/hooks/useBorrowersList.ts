/**
 * TanStack Query wrapper around `fetchBorrowersList`.
 *
 * The query key includes the full filter snapshot so deep-link
 * round-trips keep distinct caches. `placeholderData` preserves the
 * previous page's rows while paginating to avoid layout shift.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchBorrowersList } from "../api-list";
import type { BorrowerListFilters, BorrowerListResponse } from "../list-types";

export const BORROWERS_LIST_QUERY_KEY = ["borrowers", "list"] as const;

export function useBorrowersList(
  filters: BorrowerListFilters,
): UseQueryResult<BorrowerListResponse, Error> {
  return useQuery({
    queryKey: [...BORROWERS_LIST_QUERY_KEY, filters],
    queryFn: () => fetchBorrowersList(filters),
    staleTime: 30_000,
    placeholderData: (previousData) => previousData,
  });
}
