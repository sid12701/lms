/**
 * TanStack Query wrapper around `fetchBorrowerActivity`.
 *
 * Cache key: ["borrower", id, "activity"]. The activity endpoint joins
 * three audit streams (APPLICATION + PII_REVEAL + DOCUMENT_ACCESS) and
 * returns a discriminated union — see `BorrowerActivityEntry` in
 * `../types.ts`.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchBorrowerActivity } from "../api-tabs";
import type { BorrowerActivityResponse } from "../types";

export function borrowerActivityQueryKey(id: string): readonly unknown[] {
  return ["borrower", id, "activity"] as const;
}

export function useBorrowerActivity(
  id: string,
): UseQueryResult<BorrowerActivityResponse, Error> {
  return useQuery({
    queryKey: borrowerActivityQueryKey(id),
    queryFn: () => fetchBorrowerActivity(id),
    enabled: Boolean(id),
    staleTime: 30_000,
  });
}
