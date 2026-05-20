/**
 * TanStack Query wrapper around `listApiClients`.
 *
 * Cache key includes the full filter snapshot so back/forward navigation
 * hits the cache. `staleTime` 30s mirrors the alerts list.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { listApiClients } from "../api";
import type { ApiClientsListFilters, ApiClientsListResponse } from "../types";

export const API_CLIENTS_LIST_QUERY_KEY = ["api-clients", "list"] as const;

export function useApiClients(
  filters: ApiClientsListFilters,
): UseQueryResult<ApiClientsListResponse, Error> {
  return useQuery({
    queryKey: [...API_CLIENTS_LIST_QUERY_KEY, filters],
    queryFn: () => listApiClients(filters),
    staleTime: 30_000,
    placeholderData: (previousData) => previousData,
  });
}
