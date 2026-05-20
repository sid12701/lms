/**
 * TanStack Query wrapper around `fetchLoanApplicationSchedule`.
 *
 * Cache key: ["loan-application", id, "schedule"]. The mutation hook
 * `usePostRepayment` invalidates this key after a successful post.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchLoanApplicationSchedule } from "../api-tabs";
import type { LoanApplicationScheduleResponse } from "../types";

export function loanApplicationScheduleQueryKey(id: string): readonly unknown[] {
  return ["loan-application", id, "schedule"] as const;
}

export function useLoanApplicationSchedule(
  id: string,
): UseQueryResult<LoanApplicationScheduleResponse, Error> {
  return useQuery({
    queryKey: loanApplicationScheduleQueryKey(id),
    queryFn: () => fetchLoanApplicationSchedule(id),
    enabled: Boolean(id),
    staleTime: 30_000,
  });
}
