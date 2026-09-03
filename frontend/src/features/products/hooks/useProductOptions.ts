/**
 * TanStack Query wrapper around `listProductOptions`. Sibling of
 * `useLspOptions` — same reasoning, same cache policy: a short,
 * rarely-changing picker list that filter bars should share rather than each
 * re-fetching through their own `useEffect`.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { listProductOptions, type ProductOption } from "../options";

export const PRODUCT_OPTIONS_QUERY_KEY = ["products", "options"] as const;

const OPTIONS_STALE_TIME_MS = 5 * 60_000;

export function useProductOptions(): UseQueryResult<ProductOption[], Error> {
  return useQuery({
    queryKey: PRODUCT_OPTIONS_QUERY_KEY,
    queryFn: listProductOptions,
    staleTime: OPTIONS_STALE_TIME_MS,
  });
}

export function toProductFilterOption(row: ProductOption): { value: string; label: string } {
  return { value: row.id, label: `${row.name} (${row.code})` };
}
