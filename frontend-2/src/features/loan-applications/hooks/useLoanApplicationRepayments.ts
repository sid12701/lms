/**
 * TanStack Query wrapper around `fetchLoanApplicationRepayments`.
 *
 * Cache key: ["loan-application", id, "repayments"].
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchLoanApplicationRepayments } from "../api-tabs";
import type { LoanApplicationRepaymentsResponse } from "../types";

export function loanApplicationRepaymentsQueryKey(id: string): readonly unknown[] {
  return ["loan-application", id, "repayments"] as const;
}

export function useLoanApplicationRepayments(
  id: string,
): UseQueryResult<LoanApplicationRepaymentsResponse, Error> {
  return useQuery({
    queryKey: loanApplicationRepaymentsQueryKey(id),
    queryFn: () => fetchLoanApplicationRepayments(id),
    enabled: Boolean(id),
    staleTime: 30_000,
  });
}
