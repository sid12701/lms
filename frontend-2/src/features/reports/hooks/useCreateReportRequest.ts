/**
 * Mutation hook — queues a new report request via the mock router.
 *
 * The caller mints the BR-5 idempotency key (the dialog does this at submit
 * time). On success the list cache is invalidated so the new QUEUED row
 * shows up immediately + a sonner toast fires.
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { createRequest } from "../api";
import type { CreateReportRequestInput, ReportRequest } from "../types";
import { REPORT_REQUESTS_QUERY_KEY } from "./useReportRequests";

export interface CreateReportRequestVariables {
  input: CreateReportRequestInput;
  idempotencyKey: string;
}

export function useCreateReportRequest(): UseMutationResult<
  ReportRequest,
  Error,
  CreateReportRequestVariables
> {
  const queryClient = useQueryClient();
  return useMutation<ReportRequest, Error, CreateReportRequestVariables>({
    mutationFn: ({ input, idempotencyKey }) =>
      createRequest(input, { idempotencyKey }),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...REPORT_REQUESTS_QUERY_KEY],
      });
      toast.success("Report queued");
    },
  });
}
