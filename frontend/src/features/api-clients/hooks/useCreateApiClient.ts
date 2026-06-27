/**
 * Mutation hook — creates a new API client via the backend.
 *
 * Invalidates every cached `api-clients` list query on success + surfaces
 * a sonner toast. The caller mints the BR-5 idempotency key (the create
 * dialog does this at submit time).
 */
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { createApiClient } from "../api";
import type { CreateApiClientInput, CreateApiClientResponse } from "../types";
import { API_CLIENTS_LIST_QUERY_KEY } from "./useApiClients";

export function useCreateApiClient(): UseMutationResult<
  CreateApiClientResponse,
  Error,
  CreateApiClientInput
> {
  const queryClient = useQueryClient();
  return useMutation<CreateApiClientResponse, Error, CreateApiClientInput>({
    mutationFn: (input) => createApiClient(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...API_CLIENTS_LIST_QUERY_KEY],
      });
      toast.success("API client created");
    },
  });
}
