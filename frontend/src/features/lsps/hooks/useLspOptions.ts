/**
 * TanStack Query wrapper around `listLspOptions`.
 *
 * Four surfaces — the loan-applications filter bar, the reports filter bar,
 * the products dialogs, and this list itself — each ran their own
 * `useEffect` + `useState` fetch of the same short, rarely-changing list.
 * That is the second data-fetch idiom the audit flagged: four copies of the
 * request, four ad-hoc catch-and-blank error paths, and no shared cache, so
 * the same options were fetched again on every navigation.
 *
 * `staleTime` is generous because the LSP roster changes on the scale of
 * partner onboarding, not page views.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { listLspOptions, type LspOption } from "../options";

export const LSP_OPTIONS_QUERY_KEY = ["lsps", "options"] as const;

const OPTIONS_STALE_TIME_MS = 5 * 60_000;

export function useLspOptions(): UseQueryResult<LspOption[], Error> {
  return useQuery({
    queryKey: LSP_OPTIONS_QUERY_KEY,
    queryFn: listLspOptions,
    staleTime: OPTIONS_STALE_TIME_MS,
  });
}

/**
 * The filter-control shape, with the label format the three filter bars had
 * each been spelling out for themselves.
 */
export function toLspFilterOption(row: LspOption): { value: string; label: string } {
  return { value: row.id, label: `${row.name} (${row.code})` };
}
