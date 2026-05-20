/**
 * Mutation hook — updates an existing user (status, role, email, lspId).
 *
 * Invalidates the list query on success + surfaces a sonner toast. The
 * caller mints the BR-5 idempotency key at submit time.
 */
import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from "@tanstack/react-query";
import { toast } from "sonner";
import { updateUser } from "../api";
import type { UpdateUserInput, UserMutationResponse } from "../types";
import { USERS_LIST_QUERY_KEY } from "./useUsers";

export interface UpdateUserVariables extends UpdateUserInput {
  id: string;
}

export function useUpdateUser(): UseMutationResult<
  UserMutationResponse,
  Error,
  UpdateUserVariables
> {
  const queryClient = useQueryClient();
  return useMutation<UserMutationResponse, Error, UpdateUserVariables>({
    mutationFn: ({ id, ...rest }) => updateUser(id, rest),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...USERS_LIST_QUERY_KEY],
      });
      toast.success("User updated");
    },
  });
}
