/**
 * TanStack Query wrapper around `listUsers`.
 *
 * Cache key includes the full filter snapshot so back/forward navigation
 * hits the cache. `staleTime` 30s mirrors the alerts list.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { listUsers } from "../api";
import type { UsersListFilters, UsersListResponse } from "../types";

export const USERS_LIST_QUERY_KEY = ["admin", "users", "list"] as const;

export function useUsers(
  filters: UsersListFilters,
): UseQueryResult<UsersListResponse, Error> {
  return useQuery({
    queryKey: [...USERS_LIST_QUERY_KEY, filters],
    queryFn: () => listUsers(filters),
    staleTime: 30_000,
    placeholderData: (previousData) => previousData,
  });
}
