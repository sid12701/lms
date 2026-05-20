/**
 * TanStack Query wrapper around `getLspWebhookSubscription`.
 *
 * Disabled when `id` is empty so the dialog can mount without firing
 * a fetch on closed state.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { getLspWebhookSubscription } from "../api";
import type { WebhookSubscriptionResponse } from "../types";

export const LSP_WEBHOOK_QUERY_KEY = ["lsps", "webhook-subscription"] as const;

export function useLspWebhookSubscription(
  id: string | null,
): UseQueryResult<WebhookSubscriptionResponse, Error> {
  return useQuery({
    queryKey: [...LSP_WEBHOOK_QUERY_KEY, id ?? ""],
    queryFn: () => getLspWebhookSubscription(id!),
    enabled: Boolean(id),
    staleTime: 30_000,
  });
}
