/**
 * TanStack Query wrapper around `misSummary`.
 *
 * Cache key includes the filter snapshot so back/forward navigation hits the
 * cache. `staleTime` 30s matches the loan-applications list.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { misSummary } from "../api";
import type { MisFilters, MisSummary } from "../types";

const MIS_SUMMARY_QUERY_KEY = ["reports", "mis-summary"] as const;

export function useMisSummary(filters: MisFilters): UseQueryResult<MisSummary, Error> {
  return useQuery({
    queryKey: [...MIS_SUMMARY_QUERY_KEY, filters],
    queryFn: () => misSummary(filters),
    staleTime: 30_000,
    placeholderData: (previousData) => previousData,
  });
}
