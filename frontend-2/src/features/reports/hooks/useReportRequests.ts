/**
 * TanStack Query wrapper around `listRequests` with 5s polling.
 *
 * The mock router auto-advances QUEUED → PROCESSING (after 5s) → COMPLETED
 * (after 10s) on every GET. By polling every 5s the UI's polling cadence
 * drives the server-side ticker — no separate timer needed.
 *
 * `refetchInterval: 5_000` matches the QUEUED-to-PROCESSING window. We
 * intentionally keep polling on a stable cadence so a freshly queued report
 * flips PROCESSING then COMPLETED on consecutive polls without the user
 * having to refresh.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { listRequests } from "../api";
import type { ReportRequestsListResponse } from "../types";

export const REPORT_REQUESTS_QUERY_KEY = ["reports", "requests"] as const;

export function useReportRequests(): UseQueryResult<ReportRequestsListResponse, Error> {
  return useQuery({
    queryKey: [...REPORT_REQUESTS_QUERY_KEY],
    queryFn: () => listRequests(),
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
    staleTime: 0,
  });
}
