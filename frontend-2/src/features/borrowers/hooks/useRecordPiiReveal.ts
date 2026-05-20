/**
 * Mutation hook wrapping `recordPiiReveal`.
 *
 * On success invalidates:
 *   - `["borrower", id]` so the detail re-fetches (audit count tallies live there)
 *   - `["borrower", id, "activity"]` so the Activity tab refreshes its feed
 *
 * Returns the full mutation result so consumers can `mutateAsync` and pull
 * `value` out of the response to flip a `MaskedField` to its revealed state.
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import { recordPiiReveal } from "../api";
import type {
  RecordPiiRevealInput,
  RecordPiiRevealResponse,
} from "../types";
import {
  BORROWER_DETAIL_QUERY_KEY,
  borrowerDetailQueryKey,
} from "./useBorrowerDetail";

/** Activity-tab cache key — duplicated locally to avoid importing agent C's hook. */
export function borrowerActivityQueryKey(id: string) {
  return [BORROWER_DETAIL_QUERY_KEY, id, "activity"] as const;
}

export function useRecordPiiReveal(
  borrowerId: string,
): UseMutationResult<RecordPiiRevealResponse, Error, RecordPiiRevealInput> {
  const queryClient = useQueryClient();
  return useMutation<RecordPiiRevealResponse, Error, RecordPiiRevealInput>({
    mutationFn: (input) => recordPiiReveal(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: borrowerDetailQueryKey(borrowerId),
      });
      void queryClient.invalidateQueries({
        queryKey: borrowerActivityQueryKey(borrowerId),
      });
    },
  });
}
