/**
 * TanStack Query wrapper around `listLsps`.
 *
 * Cache key includes the full filter snapshot so back/forward navigation
 * hits the cache. `staleTime` 30s mirrors the alerts list.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { listLsps } from "../api";
import type { LspsListFilters, LspsListResponse } from "../types";

export const LSPS_LIST_QUERY_KEY = ["lsps", "list"] as const;

export function useLsps(filters: LspsListFilters): UseQueryResult<LspsListResponse, Error> {
  return useQuery({
    queryKey: [...LSPS_LIST_QUERY_KEY, filters],
    queryFn: () => listLsps(filters),
    staleTime: 30_000,
    placeholderData: (previousData) => previousData,
  });
}
