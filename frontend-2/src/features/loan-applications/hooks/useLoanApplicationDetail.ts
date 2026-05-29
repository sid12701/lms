/**
 * TanStack Query wrapper around `fetchLoanApplicationDetail`.
 *
 * Cache key: `["loan-application", id]` — distinct from the list cache so
 * the detail page can refetch independently after a status transition or a
 * disbursement.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchLoanApplicationDetail } from "../api-detail";
import type { LoanApplicationDetail } from "../types";

export const LOAN_APPLICATION_DETAIL_QUERY_KEY = "loan-application" as const;

/** Build the canonical query key — exported so mutation hooks can invalidate. */
export function loanApplicationDetailQueryKey(id: string) {
  return [LOAN_APPLICATION_DETAIL_QUERY_KEY, id] as const;
}

export function useLoanApplicationDetail(id: string): UseQueryResult<LoanApplicationDetail, Error> {
  return useQuery({
    queryKey: loanApplicationDetailQueryKey(id),
    queryFn: () => fetchLoanApplicationDetail(id),
    staleTime: 30_000,
    enabled: typeof id === "string" && id.length > 0,
  });
}
