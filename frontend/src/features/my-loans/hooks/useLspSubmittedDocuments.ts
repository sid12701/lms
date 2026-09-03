/**
 * TanStack Query wrapper around `listLspSubmittedDocuments`.
 *
 * The other hand-rolled `useEffect` fetch this section used to run
 * (impeccable-audit.md §10). The old effect was keyed on
 * `[applicationId, docLabels]`, and `docLabels` was derived data sitting in
 * component state — so the submitted list was fetched a second time
 * whenever the label taxonomy resolved after it did. `docLabels` is no
 * longer state (see `DocumentsSection`), so this query is keyed on
 * `applicationId` alone and fetches exactly once per application.
 *
 * `staleTime` matches `useMyLoanDetail` — same detail page, same "changes
 * on upload or an ops action, not on a timer" cadence.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { listLspSubmittedDocuments, type SubmittedLspDocument } from "../api";

export function lspSubmittedDocumentsQueryKey(applicationId: string) {
  return ["my-loans", "submitted-documents", applicationId] as const;
}

export function useLspSubmittedDocuments(
  applicationId: string,
): UseQueryResult<SubmittedLspDocument[], Error> {
  return useQuery({
    queryKey: lspSubmittedDocumentsQueryKey(applicationId),
    queryFn: () => listLspSubmittedDocuments(applicationId),
    enabled: applicationId.length > 0,
    staleTime: 30_000,
  });
}
