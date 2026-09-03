/**
 * TanStack Query wrapper around `fetchLspDocumentRequirements`.
 *
 * One of the two hand-rolled `useEffect` fetches this section used to run
 * (impeccable-audit.md §10). The intake checklist taxonomy changes on the
 * scale of product/compliance updates, not page views — the same reasoning
 * as `useLspOptions` — hence the long `staleTime`.
 *
 * `data` is `undefined` both while the request is in flight and if it fails
 * outright; `DocumentsSection` falls back to a static taxonomy in either
 * case, so this hook doesn't need to distinguish "loading" from "errored".
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { fetchLspDocumentRequirements, type LspDocumentRequirement } from "../api";

export const LSP_DOCUMENT_REQUIREMENTS_QUERY_KEY = ["my-loans", "document-requirements"] as const;

const REQUIREMENTS_STALE_TIME_MS = 10 * 60_000;

export function useLspDocumentRequirements(): UseQueryResult<LspDocumentRequirement[], Error> {
  return useQuery({
    queryKey: LSP_DOCUMENT_REQUIREMENTS_QUERY_KEY,
    queryFn: fetchLspDocumentRequirements,
    staleTime: REQUIREMENTS_STALE_TIME_MS,
  });
}
