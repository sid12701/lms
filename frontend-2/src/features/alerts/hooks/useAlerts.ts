/**
 * TanStack Query wrapper around `listAlerts`.
 *
 * Cache key includes the full filter snapshot so back/forward navigation
 * hits the cache. `staleTime` 30s mirrors the loan-applications list.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { listAlerts } from "../api";
import type { AlertsListFilters, AlertsListResponse } from "../types";

export const ALERTS_LIST_QUERY_KEY = ["alerts", "list"] as const;

export function useAlerts(
  filters: AlertsListFilters,
): UseQueryResult<AlertsListResponse, Error> {
  return useQuery({
    queryKey: [...ALERTS_LIST_QUERY_KEY, filters],
    queryFn: () => listAlerts(filters),
    staleTime: 30_000,
    placeholderData: (previousData) => previousData,
  });
}
