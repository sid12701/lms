/**
 * TanStack Query wrapper around `fetchLoanApplicationDocuments`.
 *
 * Cache key: ["loan-application", id, "documents"].
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchLoanApplicationDocuments } from "../api-tabs";
import type { LoanApplicationDocumentsResponse } from "../types";

export function loanApplicationDocumentsQueryKey(id: string): readonly unknown[] {
  return ["loan-application", id, "documents"] as const;
}

export function useLoanApplicationDocuments(
  id: string,
): UseQueryResult<LoanApplicationDocumentsResponse, Error> {
  return useQuery({
    queryKey: loanApplicationDocumentsQueryKey(id),
    queryFn: () => fetchLoanApplicationDocuments(id),
    enabled: Boolean(id),
    staleTime: 30_000,
  });
}
