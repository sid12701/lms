/**
 * Mutation hooks for the loan-application detail surface.
 *
 * Both mutations invalidate the same three caches:
 *   - this application's detail (`["loan-application", id]`)
 *   - this application's activity timeline
 *   - the global list cache (`["loan-applications", "list", ...]`)
 *
 * That keeps the right rail, action bar, audit timeline, and parent list
 * page in sync without per-mutation custom plumbing.
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import {
  postDisbursement,
  postTransition,
  type DisbursementResponse,
  type TransitionResponse,
} from "../api-detail";
import type {
  InitiateDisbursementInput,
  TransitionStatusInput,
} from "../types";
import {
  LOAN_APPLICATION_DETAIL_QUERY_KEY,
  loanApplicationDetailQueryKey,
} from "./useLoanApplicationDetail";
import { loanApplicationActivityQueryKey } from "./useLoanApplicationActivity";
import { LOAN_APPLICATIONS_LIST_QUERY_KEY } from "./useLoanApplications";

function invalidateAll(
  queryClient: ReturnType<typeof useQueryClient>,
  id: string,
): void {
  void queryClient.invalidateQueries({ queryKey: loanApplicationDetailQueryKey(id) });
  void queryClient.invalidateQueries({
    queryKey: loanApplicationActivityQueryKey(id),
  });
  void queryClient.invalidateQueries({
    queryKey: [LOAN_APPLICATION_DETAIL_QUERY_KEY, id],
  });
  void queryClient.invalidateQueries({
    queryKey: LOAN_APPLICATIONS_LIST_QUERY_KEY,
  });
}

/**
 * Hook wrapping `postTransition`. Returns a TanStack mutation; consumers
 * pass the `TransitionStatusInput` (including the BR-5 idempotency key
 * minted by `TransitionConfirmDialog`).
 */
export function useTransitionStatus(
  id: string,
): UseMutationResult<TransitionResponse, Error, TransitionStatusInput> {
  const queryClient = useQueryClient();
  return useMutation<TransitionResponse, Error, TransitionStatusInput>({
    mutationFn: (input) => postTransition(id, input),
    onSuccess: () => invalidateAll(queryClient, id),
  });
}

/**
 * Hook wrapping `postDisbursement`. Same invalidation set as
 * `useTransitionStatus`.
 */
export function useInitiateDisbursement(
  id: string,
): UseMutationResult<DisbursementResponse, Error, InitiateDisbursementInput> {
  const queryClient = useQueryClient();
  return useMutation<DisbursementResponse, Error, InitiateDisbursementInput>({
    mutationFn: (input) => postDisbursement(id, input),
    onSuccess: () => invalidateAll(queryClient, id),
  });
}
