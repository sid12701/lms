/**
 * TanStack Query wrapper around `fetchLoanApplicationActivity`. The query
 * is keyed by application id + the literal "activity" so it stays distinct
 * from the detail and webhooks caches.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchLoanApplicationActivity } from "../api-detail";
import { LOAN_APPLICATION_DETAIL_QUERY_KEY } from "./useLoanApplicationDetail";
import type { LoanApplicationActivityResponse } from "../types";

export function loanApplicationActivityQueryKey(id: string) {
  return [LOAN_APPLICATION_DETAIL_QUERY_KEY, id, "activity"] as const;
}

export interface UseLoanApplicationActivityOptions {
  /** When false, the query is paused — used to gate by active tab. */
  enabled?: boolean;
}

export function useLoanApplicationActivity(
  id: string,
  options: UseLoanApplicationActivityOptions = {},
): UseQueryResult<LoanApplicationActivityResponse, Error> {
  return useQuery({
    queryKey: loanApplicationActivityQueryKey(id),
    queryFn: () => fetchLoanApplicationActivity(id),
    staleTime: 30_000,
    enabled: typeof id === "string" && id.length > 0 && (options.enabled ?? true),
  });
}
