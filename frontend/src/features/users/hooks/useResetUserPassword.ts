/**
 * Mutation hook — issues a fresh temporary password for an existing user.
 *
 * The mutation result carries the one-time `temporaryPassword`; the dialog
 * surfaces it via `TempPasswordRevealCard` and only acknowledges once the
 * operator has saved it.
 */
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { toast } from "sonner";
import { resetUserPassword } from "../api";
import type { ResetUserPasswordInput, ResetUserPasswordResponse } from "../types";
import { USERS_LIST_QUERY_KEY } from "./useUsers";

export interface ResetUserPasswordVariables extends ResetUserPasswordInput {
  id: string;
}

export function useResetUserPassword(): UseMutationResult<
  ResetUserPasswordResponse,
  Error,
  ResetUserPasswordVariables
> {
  const queryClient = useQueryClient();
  return useMutation<ResetUserPasswordResponse, Error, ResetUserPasswordVariables>({
    mutationFn: ({ id, ...rest }) => resetUserPassword(id, rest),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...USERS_LIST_QUERY_KEY],
      });
      toast.success("Password reset. Save the temporary password.");
    },
  });
}
