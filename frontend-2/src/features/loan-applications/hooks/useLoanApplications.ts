/**
 * TanStack Query wrapper around `fetchLoanApplications`.
 *
 * The query key includes the full filter snapshot so back/forward and
 * deep-link round-trips keep distinct caches. `placeholderData` preserves
 * the previous page's rows while paginating to avoid layout shift.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchLoanApplications } from "../api";
import type {
  LoanApplicationListFilters,
  LoanApplicationListResponse,
} from "../types";

export const LOAN_APPLICATIONS_LIST_QUERY_KEY = ["loan-applications", "list"] as const;

export function useLoanApplications(
  filters: LoanApplicationListFilters,
): UseQueryResult<LoanApplicationListResponse, Error> {
  return useQuery({
    queryKey: [...LOAN_APPLICATIONS_LIST_QUERY_KEY, filters],
    queryFn: () => fetchLoanApplications(filters),
    staleTime: 30_000,
    placeholderData: (previousData) => previousData,
  });
}
