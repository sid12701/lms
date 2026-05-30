/**
 * Mutation hook — posts a repayment via `postRepayment(id, input)`.
 *
 * Invalidates the schedule + repayments + detail caches on success, plus
 * the list-query key (BR-14/BR-15 status side-effects mean the list row
 * may change tile + status). The caller mints the BR-5 idempotency key.
 */
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { postRepayment } from "../api-tabs";
import type { PostRepaymentInput } from "../types";
import { LOAN_APPLICATIONS_LIST_QUERY_KEY } from "./useLoanApplications";
import { loanApplicationRepaymentsQueryKey } from "./useLoanApplicationRepayments";
import { loanApplicationScheduleQueryKey } from "./useLoanApplicationSchedule";

export function usePostRepayment(id: string): UseMutationResult<void, Error, PostRepaymentInput> {
  const queryClient = useQueryClient();
  return useMutation<void, Error, PostRepaymentInput>({
    mutationFn: (input) => postRepayment(id, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: loanApplicationScheduleQueryKey(id) });
      void queryClient.invalidateQueries({ queryKey: loanApplicationRepaymentsQueryKey(id) });
      void queryClient.invalidateQueries({ queryKey: ["loan-application", id] });
      void queryClient.invalidateQueries({ queryKey: [...LOAN_APPLICATIONS_LIST_QUERY_KEY] });
    },
  });
}
