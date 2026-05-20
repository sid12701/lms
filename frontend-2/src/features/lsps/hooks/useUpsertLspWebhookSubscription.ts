/**
 * Mutation hook — upserts a webhook subscription for an LSP.
 *
 * Invalidates BOTH the per-LSP subscription cache + the list (the list-row
 * `webhookEnabled` projection flips on toggle).
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { upsertLspWebhookSubscription } from "../api";
import type {
  UpsertWebhookSubscriptionInput,
  WebhookSubscriptionResponse,
} from "../types";
import { LSPS_LIST_QUERY_KEY } from "./useLsps";
import { LSP_WEBHOOK_QUERY_KEY } from "./useLspWebhookSubscription";

export interface UpsertLspWebhookVariables
  extends UpsertWebhookSubscriptionInput {
  id: string;
}

export function useUpsertLspWebhookSubscription(): UseMutationResult<
  WebhookSubscriptionResponse,
  Error,
  UpsertLspWebhookVariables
> {
  const queryClient = useQueryClient();
  return useMutation<
    WebhookSubscriptionResponse,
    Error,
    UpsertLspWebhookVariables
  >({
    mutationFn: ({ id, ...rest }) => upsertLspWebhookSubscription(id, rest),
    onSuccess: (_res, variables) => {
      void queryClient.invalidateQueries({
        queryKey: [...LSP_WEBHOOK_QUERY_KEY, variables.id],
      });
      void queryClient.invalidateQueries({ queryKey: [...LSPS_LIST_QUERY_KEY] });
      toast.success("Webhook subscription saved");
    },
  });
}
