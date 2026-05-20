/**
 * TanStack Query wrapper around `fetchHomeKpis`.
 *
 * Cache key: `["home", "kpis"]` — invalidate from anywhere that should refresh
 * the dashboard (e.g. status transitions, repayment posting). Stale time
 * matches the §9 default; we do NOT refetch on window focus because the home
 * page is read-mostly and per-tab churn would be noisy.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchHomeKpis } from "../api";
import type { HomeKpis } from "../types";

export const HOME_KPIS_QUERY_KEY = ["home", "kpis"] as const;

export function useHomeKpis(): UseQueryResult<HomeKpis, Error> {
  return useQuery({
    queryKey: HOME_KPIS_QUERY_KEY,
    queryFn: fetchHomeKpis,
    staleTime: 30_000,
  });
}
