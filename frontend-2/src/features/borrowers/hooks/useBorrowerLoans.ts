/**
 * TanStack Query wrapper around `fetchBorrowerLoans`.
 *
 * Cache key: ["borrower", id, "loans"]. The borrower-detail surface is
 * read-only, so no mutation hook invalidates this query directly today.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchBorrowerLoans } from "../api-tabs";
import type { BorrowerLoansResponse } from "../types";

export function borrowerLoansQueryKey(id: string): readonly unknown[] {
  return ["borrower", id, "loans"] as const;
}

export function useBorrowerLoans(
  id: string,
): UseQueryResult<BorrowerLoansResponse, Error> {
  return useQuery({
    queryKey: borrowerLoansQueryKey(id),
    queryFn: () => fetchBorrowerLoans(id),
    enabled: Boolean(id),
    staleTime: 30_000,
  });
}
