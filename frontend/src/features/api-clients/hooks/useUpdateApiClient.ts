/**
 * Mutation hook — updates an API client's name and/or status via the backend
 * `PUT /api-clients/{id}` endpoint (enable/disable + rename).
 *
 * Invalidates the cached api-clients list query on success + surfaces a sonner
 * toast. The caller mints the BR-5 idempotency key (the edit dialog does this
 * at submit time).
 */
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { updateApiClient } from "../api";
import type { ApiClientMutationResponse, UpdateApiClientInput } from "../types";
import { API_CLIENTS_LIST_QUERY_KEY } from "./useApiClients";

export interface UpdateApiClientVariables extends UpdateApiClientInput {
  id: string;
}

export function useUpdateApiClient(): UseMutationResult<
  ApiClientMutationResponse,
  Error,
  UpdateApiClientVariables
> {
  const queryClient = useQueryClient();
  return useMutation<ApiClientMutationResponse, Error, UpdateApiClientVariables>({
    mutationFn: ({ id, ...rest }) => updateApiClient(id, rest),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...API_CLIENTS_LIST_QUERY_KEY],
      });
      toast.success("API client updated");
    },
  });
}
