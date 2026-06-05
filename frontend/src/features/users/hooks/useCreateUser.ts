/**
 * Mutation hook â€” creates a new admin / LSP user via the backend.
 *
 * The mutation result carries the one-time `temporaryPassword`; the dialog
 * surfaces it via `TempPasswordRevealCard` and only acknowledges once the
 * operator has saved it.
 */
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { createUser } from "../api";
import type { CreateUserInput, CreateUserResponse } from "../types";
import { USERS_LIST_QUERY_KEY } from "./useUsers";

export function useCreateUser(): UseMutationResult<CreateUserResponse, Error, CreateUserInput> {
  const queryClient = useQueryClient();
  return useMutation<CreateUserResponse, Error, CreateUserInput>({
    mutationFn: (input) => createUser(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...USERS_LIST_QUERY_KEY],
      });
      toast.success("User created. Save the temporary password.");
    },
  });
}
