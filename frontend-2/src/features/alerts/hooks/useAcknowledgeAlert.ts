/**
 * Mutation hook — acknowledges a single alert via the live backend.
 *
 * Invalidates every cached `alerts` query on success + surfaces a sonner
 * toast. The caller mints the BR-5 idempotency key (the dialog does this
 * at submit time).
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { acknowledgeAlert } from "../api";
import type {
  AcknowledgeAlertInput,
  AcknowledgeAlertResponse,
} from "../types";
import { ALERTS_LIST_QUERY_KEY } from "./useAlerts";

export interface AcknowledgeAlertVariables extends AcknowledgeAlertInput {
  id: string;
}

export function useAcknowledgeAlert(): UseMutationResult<
  AcknowledgeAlertResponse,
  Error,
  AcknowledgeAlertVariables
> {
  const queryClient = useQueryClient();
  return useMutation<AcknowledgeAlertResponse, Error, AcknowledgeAlertVariables>({
    mutationFn: ({ id, note, idempotencyKey }) =>
      acknowledgeAlert(id, { note, idempotencyKey }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...ALERTS_LIST_QUERY_KEY] });
      toast.success("Alert acknowledged");
    },
  });
}
