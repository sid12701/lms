/**
 * TanStack Query wrapper around `fetchAuditEvents`.
 *
 * The query key includes the full filter snapshot so deep-links round-trip
 * cleanly through the cache. `placeholderData` keeps the previous page on
 * screen while the next one resolves to avoid layout shift.
 *
 * `enabled` defaults to true — the page-level role guard prevents the
 * hook from mounting for non-SYSTEM_ADMIN sessions, so we never need to
 * gate fetches here.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchAuditEvents } from "../api";
import type { AuditEventsFilters, AuditEventsResponse } from "../types";

export const AUDIT_EVENTS_QUERY_KEY = ["audit", "events"] as const;

export interface UseAuditEventsOptions {
  enabled?: boolean;
}

export function useAuditEvents(
  filters: AuditEventsFilters,
  options: UseAuditEventsOptions = {},
): UseQueryResult<AuditEventsResponse, Error> {
  return useQuery({
    queryKey: [...AUDIT_EVENTS_QUERY_KEY, filters],
    queryFn: () => fetchAuditEvents(filters),
    staleTime: 15_000,
    placeholderData: (previousData) => previousData,
    enabled: options.enabled ?? true,
  });
}
