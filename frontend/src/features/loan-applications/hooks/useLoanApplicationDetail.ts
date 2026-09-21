/**
 * TanStack Query wrapper around `fetchLoanApplicationDetail`.
 *
 * Cache key: `["loan-application", id]` — distinct from the list cache so
 * the detail page can refetch independently after a status transition or a
 * disbursement.
 *
 * L02 — bounded disbursement polling. While the application sits in a
 * genuinely in-flight disbursement status (queued for the payout adapter or
 * scheduled for an automatic worker retry) the query refetches every
 * {@link DISBURSEMENT_POLL_INTERVAL_MS}; polling stops as soon as the status
 * leaves that set — including a terminal failure — and never runs past
 * {@link DISBURSEMENT_POLL_WINDOW_MS} of continuous in-flight time. Terminal
 * and historical statuses never poll.
 */
import { useRef } from "react";
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchLoanApplicationDetail } from "../api-detail";
import type { LoanApplicationDetail } from "../types";

export const LOAN_APPLICATION_DETAIL_QUERY_KEY = "loan-application" as const;

export const DISBURSEMENT_POLL_INTERVAL_MS = 15_000;
export const DISBURSEMENT_POLL_WINDOW_MS = 10 * 60_000;

const DISBURSEMENT_IN_FLIGHT_STATUSES: ReadonlySet<string> = new Set([
  "APPROVED_PENDING_DISBURSAL",
  "DISBURSEMENT_RETRY",
]);

/** Build the canonical query key — exported so mutation hooks can invalidate. */
export function loanApplicationDetailQueryKey(id: string) {
  return [LOAN_APPLICATION_DETAIL_QUERY_KEY, id] as const;
}

/**
 * Pure poll-decision so the boundary conditions are testable without a
 * renderer: returns the poll interval while `status` is in-flight and the
 * window has not expired, `false` otherwise.
 */
export function resolveDisbursementPollInterval(
  status: string | undefined,
  elapsedInFlightMs: number,
): number | false {
  if (!status || !DISBURSEMENT_IN_FLIGHT_STATUSES.has(status)) return false;
  if (elapsedInFlightMs >= DISBURSEMENT_POLL_WINDOW_MS) return false;
  return DISBURSEMENT_POLL_INTERVAL_MS;
}

export function useLoanApplicationDetail(id: string): UseQueryResult<LoanApplicationDetail, Error> {
  // Set on the first in-flight poll decision, cleared whenever the status
  // leaves the in-flight set — a later retry starts a fresh window.
  const inFlightSince = useRef<number | null>(null);

  return useQuery({
    queryKey: loanApplicationDetailQueryKey(id),
    queryFn: ({ signal }) => fetchLoanApplicationDetail(id, signal),
    staleTime: 30_000,
    enabled: typeof id === "string" && id.length > 0,
    refetchInterval: (query) => {
      const status = query.state.data?.application.status;
      if (!status || !DISBURSEMENT_IN_FLIGHT_STATUSES.has(status)) {
        inFlightSince.current = null;
        return false;
      }
      const now = Date.now();
      inFlightSince.current ??= now;
      return resolveDisbursementPollInterval(status, now - inFlightSince.current);
    },
  });
}
