import { useQuery } from "@tanstack/react-query";
import type { DisbursementPreviewData } from "@/components/app/disbursement/DisbursementPreviewSummary";

export interface PreviewState {
  data: DisbursementPreviewData | null;
  loading: boolean;
  error: string | null;
}

const EMPTY_PREVIEW: PreviewState = { data: null, loading: false, error: null };

export function disbursementPreviewQueryKey(applicationId: string) {
  return ["lifecycle", "disbursement-preview", applicationId] as const;
}

/**
 * Loads the disbursement preview for the open dialog via `useQuery`, keyed
 * on `applicationId` and gated by `enabled` — a closed dialog, a
 * non-disbursement action, or a caller that didn't wire `load` never
 * fetches, which `queryKey` + `enabled` give natively instead of the old
 * hand-rolled `request` identity + cancellation-flag dance.
 *
 * `staleTime: 0` and `retry: false`: this is the money figure an operator
 * is about to confirm as irreversible, so a single attempt must either
 * produce a fresh number or a clear error — never a silently retried
 * request, and never data old enough to be considered reusable.
 * `TransitionConfirmDialog` also removes this query's cache entry the
 * moment the dialog closes (`useFlushOnClose`), because react-query would
 * otherwise keep serving the last preview on the *next* open while a fresh
 * fetch races it in the background. This dialog must never render a number
 * it has not just fetched for the open it is currently showing.
 */
export function useDisbursementPreview({
  enabled,
  applicationId,
  load,
}: {
  enabled: boolean;
  applicationId?: string;
  load?: (applicationId: string) => Promise<DisbursementPreviewData>;
}): PreviewState {
  const queryEnabled = enabled && Boolean(applicationId) && Boolean(load);

  const query = useQuery<DisbursementPreviewData, Error>({
    queryKey: disbursementPreviewQueryKey(applicationId ?? ""),
    queryFn: () => {
      if (!applicationId || !load) {
        // Unreachable: `enabled` guards both preconditions below.
        return Promise.reject(new Error("Disbursement preview requested without an application."));
      }
      return load(applicationId);
    },
    enabled: queryEnabled,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });

  if (!queryEnabled) return EMPTY_PREVIEW;

  return {
    data: query.isSuccess ? query.data : null,
    loading: query.isPending,
    error: query.isError
      ? query.error instanceof Error
        ? query.error.message
        : "Could not load disbursement preview."
      : null,
  };
}
