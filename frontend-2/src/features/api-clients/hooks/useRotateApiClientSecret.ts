/**
 * Mutation hook — rotates the secret on an existing API client.
 *
 * Invalidates the cached api-clients list query on success + surfaces a sonner
 * toast. The caller mints the BR-5 idempotency key (the rotate dialog does
 * this at submit time via the shared `RotateSecretDialog` primitive).
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { rotateApiClientSecret } from "../api";
import type {
  RotateApiClientSecretInput,
  RotateApiClientSecretResponse,
} from "../types";
import { API_CLIENTS_LIST_QUERY_KEY } from "./useApiClients";

export interface RotateApiClientSecretVariables
  extends RotateApiClientSecretInput {
  id: string;
}

export function useRotateApiClientSecret(): UseMutationResult<
  RotateApiClientSecretResponse,
  Error,
  RotateApiClientSecretVariables
> {
  const queryClient = useQueryClient();
  return useMutation<
    RotateApiClientSecretResponse,
    Error,
    RotateApiClientSecretVariables
  >({
    mutationFn: ({ id, ...rest }) => rotateApiClientSecret(id, rest),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...API_CLIENTS_LIST_QUERY_KEY],
      });
      toast.success("API client secret rotated");
    },
  });
}
